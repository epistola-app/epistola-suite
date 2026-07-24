// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.tenantbackup

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The restore schema-compatibility gate ([app.epistola.suite.tenantbackup.schema.RestoreCompatibility]):
 * a cross-schema restore is allowed only when every crossed migration is declared compatible in the
 * right direction (each migration's `backup-restore-compatibility` header for backward; the backup's recorded flags for
 * forward), else refused before any write. The listed migration in the file (`20260618204750`, the
 * `support-compatibility-check` rename, marked backward+forward) is the fixture.
 */
class RestoreCompatibilityIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var crypto: TenantBackupCrypto

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var restoreCompatibility: app.epistola.suite.tenantbackup.schema.RestoreCompatibility

    @Autowired
    lateinit var jdbi: org.jdbi.v3.core.Jdbi

    @Test
    fun `backwardBoundary classifies every applied version the same way check does`() {
        // The O(1) list shortcut (boundary) must match the authoritative per-backup check (F3).
        jdbi.useHandle<Exception> { handle ->
            val live = app.epistola.suite.tenantbackup.schema.SchemaStamp.current(handle)
            val boundary = restoreCompatibility.backwardBoundary(handle, live)
            val applied =
                handle
                    .createQuery("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL AND version <= :live")
                    .bind("live", live)
                    .mapTo(String::class.java)
                    .list()
            applied.forEach { stamp ->
                val viaBoundary = boundary == null || stamp >= boundary
                val viaCheck = restoreCompatibility.check(handle, stamp, appliedMigrations = null) is app.epistola.suite.tenantbackup.schema.Compatibility.Compatible
                assertThat(viaBoundary).describedAs("restorability of backup at %s", stamp).isEqualTo(viaCheck)
            }
        }
    }

    @Test
    fun `refuses an older backup whose crossed migrations are not declared backward-compatible`() {
        val backup = freshBackup("Compat Old")
        // A far-past stamp makes every applied migration "crossed", and most are unlisted → deny.
        val tampered = rewrite(backup.bytes) { it.copy(schemaStamp = "00000000000000") }

        assertThatThrownBy { withMediator { RestoreTenantBackup(backup.tenant, tampered).execute() } }
            .isInstanceOf(IncompatibleBackupSchemaException::class.java)
            .hasMessageContaining("backward-compatible")

        // Refused before any mutation.
        withMediator { assertThat(ListCatalogs(backup.tenant).query().map { it.id.value }).contains("main") }
    }

    @Test
    fun `backward restore is allowed when no undeclared migration is crossed, and the declared migration is crossable`() {
        // A same-schema restore crosses nothing, so the gate allows it and the restore runs. (We can no
        // longer construct a "crosses ONLY the declared migration" restore against the live schema: the
        // declared data-only rename 20260618204750 is not the backward boundary — other undeclared
        // (structural baseline) migrations are, so they always dominate the boundary computation.)
        val backup = freshBackup("Compat Backward")

        val result = withMediator { RestoreTenantBackup(backup.tenant, backup.bytes).execute() }
        assertThat(result.rowsRestored).isGreaterThan(0)

        // The declared data-only migration is genuinely treated as backward-crossable — it is never the
        // backward boundary; the boundary is an undeclared (structural) migration.
        assertThat(restoreCompatibility.flagsFor("20260618204750").backward).isTrue()
        jdbi.useHandle<Exception> { handle ->
            val live = app.epistola.suite.tenantbackup.schema.SchemaStamp.current(handle)
            assertThat(restoreCompatibility.backwardBoundary(handle, live)).isNotEqualTo("20260618204750")
        }
    }

    @Test
    fun `forward restore across a declared-forward-compatible migration succeeds`() {
        val backup = freshBackup("Compat Forward OK")
        val newer =
            rewrite(backup.bytes) { m ->
                m.copy(
                    schemaStamp = FUTURE,
                    appliedMigrations = m.appliedMigrations.orEmpty() + BackupMigration(FUTURE, backward = true, forward = true),
                )
            }

        val result = withMediator { RestoreTenantBackup(backup.tenant, newer).execute() }
        assertThat(result.rowsRestored).isGreaterThan(0)
    }

    @Test
    fun `forward restore across a non-forward-compatible migration is refused`() {
        val backup = freshBackup("Compat Forward No")
        val newer =
            rewrite(backup.bytes) { m ->
                m.copy(
                    schemaStamp = FUTURE,
                    appliedMigrations = m.appliedMigrations.orEmpty() + BackupMigration(FUTURE, backward = true, forward = false),
                )
            }

        assertThatThrownBy { withMediator { RestoreTenantBackup(backup.tenant, newer).execute() } }
            .isInstanceOf(IncompatibleBackupSchemaException::class.java)
            .hasMessageContaining("forward-compatible")
    }

    @Test
    fun `forward restore of a v1 backup with no recorded migrations is refused`() {
        val backup = freshBackup("Compat Forward V1")
        val newer = rewrite(backup.bytes) { it.copy(schemaStamp = FUTURE, appliedMigrations = null) }

        assertThatThrownBy { withMediator { RestoreTenantBackup(backup.tenant, newer).execute() } }
            .isInstanceOf(IncompatibleBackupSchemaException::class.java)
            .hasMessageContaining("forward-restore support")
    }

    private data class Backup(
        val tenant: app.epistola.suite.common.ids.TenantKey,
        val bytes: ByteArray,
    )

    private fun freshBackup(name: String): Backup {
        val tenant = createTenant(name)
        withMediator { CreateCatalog(tenantKey = tenant.id, id = CatalogKey.of("main"), name = "Main").execute() }
        val artifact = withMediator { BuildTenantBackup(tenant.id).execute()!! }
        return Backup(tenant.id, artifact.bytes)
    }

    /** Decrypts, rewrites `backup.json` via [transform], and re-encrypts the artifact. */
    private fun rewrite(
        artifactBytes: ByteArray,
        transform: (TenantBackupManifest) -> TenantBackupManifest,
    ): ByteArray {
        val archive = crypto.unwrap(artifactBytes)
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { out ->
            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val content = zip.readBytes()
                    out.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == "backup.json") {
                        val manifest = objectMapper.readValue(content, TenantBackupManifest::class.java)
                        out.write(objectMapper.writeValueAsBytes(transform(manifest)))
                    } else {
                        out.write(content)
                    }
                    out.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return crypto.wrap(baos.toByteArray())
    }

    private companion object {
        const val FUTURE = "20270101000000"
    }
}

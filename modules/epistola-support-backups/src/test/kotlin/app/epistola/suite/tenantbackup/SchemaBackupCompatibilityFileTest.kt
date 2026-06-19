package app.epistola.suite.tenantbackup

import app.epistola.suite.mediator.execute
import app.epistola.suite.tenantbackup.schema.RestoreCompatibility
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Guards `schema-backup-compatibility.yaml`: every declared version must be a real migration (so a
 * typo can't silently mis-gate), and a fresh backup must record the file's flags into the manifest
 * for forward-restore support.
 */
class SchemaBackupCompatibilityFileTest : IntegrationTestBase() {
    @Autowired
    lateinit var restoreCompatibility: RestoreCompatibility

    @Autowired
    lateinit var jdbi: org.jdbi.v3.core.Jdbi

    @Autowired
    lateinit var crypto: TenantBackupCrypto

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Test
    fun `every declared compatibility version is a real applied migration`() {
        val applied =
            jdbi.withHandle<Set<String>, Exception> { handle ->
                handle
                    .createQuery("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL")
                    .mapTo(String::class.java)
                    .set()
            }
        assertThat(applied).containsAll(restoreCompatibility.declaredVersions())
    }

    @Test
    fun `a fresh backup is v2 and records applied migrations with their compatibility flags`() {
        val tenant = createTenant("Compat Manifest")
        val artifact = withMediator { BuildTenantBackup(tenant.id).execute() }
        val manifest = readManifest(artifact.bytes)

        assertThat(manifest.formatVersion).isEqualTo(2)
        assertThat(manifest.appliedMigrations).isNotNull()
        // The listed migration is recorded with the file's flags (backward + forward).
        val rename = manifest.appliedMigrations!!.single { it.version == "20260618204750" }
        assertThat(rename.backward).isTrue()
        assertThat(rename.forward).isTrue()
    }

    private fun readManifest(artifactBytes: ByteArray): TenantBackupManifest {
        ZipInputStream(ByteArrayInputStream(crypto.unwrap(artifactBytes))).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "backup.json") {
                    return objectMapper.readValue(zip.readBytes(), TenantBackupManifest::class.java)
                }
                entry = zip.nextEntry
            }
        }
        error("backup.json not found")
    }
}

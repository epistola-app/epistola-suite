// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.tenantbackup

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.DeleteAsset
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * A backup has to carry the bytes a *release* holds, not only those a live asset points at.
 *
 * Once a catalog is released its content is retained as revisions, and `revision_binaries` names
 * the blobs that content needs. Delete the image from the working copy and no `assets` row reaches
 * those bytes any more — so a dump that only followed assets would omit them, and the restored
 * tenant would hold a release it could no longer reproduce.
 *
 * The restore direction is the other half: `revision_binaries` takes a non-deferrable foreign key
 * into `asset_content`, so the bytes must be written before the row naming them. This fails on that
 * key if blobs are restored after the tables.
 */
class BackupRevisionBinaryIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var jdbi: Jdbi

    @Test
    fun `a backup carries and restores the bytes a released revision holds`() {
        val tenant = createTenant("Revision Blob")
        val main = CatalogKey.of("main")
        val bytes = ByteArray(300) { ((it * 7) % 256).toByte() }

        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = main, name = "Main").execute()
            val asset = UploadAsset(
                tenantId = tenant.id,
                name = "held.png",
                mediaType = AssetMediaType.PNG,
                content = bytes,
                width = 1,
                height = 1,
                catalogKey = main,
            ).execute().id
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = main, version = "1.0.0").execute()
            // Only the release holds these bytes from here on.
            DeleteAsset(tenant.id, asset).execute()
        }

        val scope = "global"
        val contentHash = heldHash(tenant.id.value)
        assertThat(contentHash).`as`("the release recorded the bytes it needs").isNotNull()

        val backup = withMediator { BuildTenantBackup(tenant.id).execute()!! }

        // Diverge: drop the retained content and its bytes, innermost reference first. Each step
        // is refused while something still names what it would remove, which is the retention rule
        // working — so the order here is the schema's, not a preference.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("DELETE FROM release_entries WHERE tenant_key = :t").bind("t", tenant.id).execute()
            handle.createUpdate("DELETE FROM resource_revisions WHERE tenant_key = :t").bind("t", tenant.id).execute()
            handle.createUpdate("DELETE FROM asset_content WHERE scope = :s AND content_hash = :h")
                .bind("s", scope).bind("h", contentHash).execute()
        }

        withMediator { RestoreTenantBackup(tenant.id, backup.bytes).execute() }

        assertThat(restoredBytes(scope, contentHash!!))
            .`as`("the bytes came back, byte for byte, with no asset pointing at them")
            .isEqualTo(bytes)
        assertThat(heldHash(tenant.id.value))
            .`as`("and the revision that needs them is holding them again")
            .isEqualTo(contentHash)
    }

    /**
     * Raw SQL: `revision_binaries` has no read query — nothing reads revisions yet, which is the
     * point of the stage. This asserts against the rows the release wrote.
     */
    private fun heldHash(tenantKey: String): String? = jdbi.withHandle<String?, Exception> { handle ->
        handle.createQuery("SELECT content_hash FROM revision_binaries WHERE tenant_key = :t")
            .bind("t", tenantKey)
            .mapTo(String::class.java)
            .findFirst()
            .orElse(null)
    }

    private fun restoredBytes(scope: String, hash: String): ByteArray? = jdbi.withHandle<ByteArray?, Exception> { handle ->
        handle.createQuery("SELECT content FROM asset_content WHERE scope = :s AND content_hash = :h")
            .bind("s", scope)
            .bind("h", hash)
            .map { rs, _ -> rs.getBytes("content") }
            .findOne()
            .orElse(null)
    }
}

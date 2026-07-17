package app.epistola.suite.tenantbackup

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.GLOBAL_ASSET_SCOPE
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.fonts.model.sha256Hex
import app.epistola.suite.mediator.execute
import app.epistola.suite.storage.AssetContentStore
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Regression for the asset-blob path: a backup of a tenant that has an `asset_content`
 * blob must build (the bytes are carried raw, not base64 — Postgres
 * `encode(…, 'base64')` wraps lines, which Java's basic decoder rejects) and restore
 * the exact bytes. Post-#738 the blob lives in the content-addressable `asset_content`
 * store, keyed by `(scope, content_hash)`; non-sensitive assets scope to 'global'.
 */
class BackupAssetBlobIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var jdbi: Jdbi

    @Autowired
    lateinit var assetContentStore: AssetContentStore

    @Test
    fun `a backup captures and restores an asset blob byte-for-byte`() {
        val tenant = createTenant("Asset Blob")
        val main = CatalogKey.of("main")
        // 300 bytes — large enough that base64 wraps onto multiple lines, the case that broke decode.
        val bytes = ByteArray(300) { (it % 256).toByte() }
        // Non-sensitive asset → dedup scope is 'global'; hash is derived from the bytes.
        val scope = GLOBAL_ASSET_SCOPE
        val contentHash = sha256Hex(bytes)

        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = main, name = "Main").execute()
            UploadAsset(
                tenantId = tenant.id,
                name = "logo.png",
                mediaType = AssetMediaType.PNG,
                content = bytes,
                width = 1,
                height = 1,
                catalogKey = main,
            ).execute()
        }

        val backup = withMediator { BuildTenantBackup(tenant.id).execute()!! }
        // At least our uploaded blob; the tenant also subscribes to the shared
        // `system` catalog, whose seeded badge asset contributes its own blob.
        // The byte-for-byte restore below is the real check.
        assertThat(backup.blobCount).isGreaterThanOrEqualTo(1)

        // Diverge: wipe the stored bytes (raw SQL — simulating loss; no command deletes an
        // asset_content blob), then restore them from the backup and read back via the port.
        jdbi.useHandle<Exception> {
            it.createUpdate("DELETE FROM asset_content WHERE scope = :s AND content_hash = :h")
                .bind("s", scope)
                .bind("h", contentHash)
                .execute()
        }
        withMediator { RestoreTenantBackup(tenant.id, backup.bytes).execute() }

        val restored = assetContentStore.get(scope, contentHash)?.content?.readAllBytes()
        assertThat(restored).isEqualTo(bytes)
    }
}

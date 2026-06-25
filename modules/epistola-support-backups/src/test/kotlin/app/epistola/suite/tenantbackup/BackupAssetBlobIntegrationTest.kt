package app.epistola.suite.tenantbackup

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Regression for the asset-blob path: a backup of a tenant that has a `content_store` asset blob
 * must build (the bytes are carried raw, not base64 — Postgres `encode(…, 'base64')` wraps lines,
 * which Java's basic decoder rejects) and restore the exact bytes. Tenants without assets never
 * exercised this, so the bug only surfaced in a real run.
 */
class BackupAssetBlobIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var jdbi: Jdbi

    @Test
    fun `a backup captures and restores an asset blob byte-for-byte`() {
        val tenant = createTenant("Asset Blob")
        val main = CatalogKey.of("main")
        // 300 bytes — large enough that base64 wraps onto multiple lines, the case that broke decode.
        val bytes = ByteArray(300) { (it % 256).toByte() }

        val assetKey =
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
                ).execute().id
            }
        val blobKey = "assets/${tenant.id.value}/${assetKey.value}"

        val backup = withMediator { BuildTenantBackup(tenant.id).execute()!! }
        // At least our uploaded blob; the tenant also subscribes to the shared
        // `system` catalog, whose seeded badge asset contributes its own blob.
        // The byte-for-byte restore below is the real check.
        assertThat(backup.blobCount).isGreaterThanOrEqualTo(1)

        // Diverge: wipe the stored bytes, then restore them from the backup.
        jdbi.useHandle<Exception> { it.createUpdate("DELETE FROM content_store WHERE key = :k").bind("k", blobKey).execute() }
        withMediator { RestoreTenantBackup(tenant.id, backup.bytes).execute() }

        val restored =
            jdbi.withHandle<ByteArray?, Exception> { h ->
                h
                    .createQuery("SELECT content FROM content_store WHERE key = :k")
                    .bind("k", blobKey)
                    .map { rs, _ -> rs.getBytes("content") }
                    .findOne()
                    .orElse(null)
            }
        assertThat(restored).isEqualTo(bytes)
    }
}

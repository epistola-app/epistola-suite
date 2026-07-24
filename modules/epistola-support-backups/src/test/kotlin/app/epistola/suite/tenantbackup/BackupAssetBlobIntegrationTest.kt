// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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
 * Regression for the asset-blob path: a backup of a tenant that has an `asset_content`
 * blob must build (the bytes are carried raw, not base64 — Postgres
 * `encode(…, 'base64')` wraps lines, which Java's basic decoder rejects) and restore
 * the exact bytes. Post-#738 the blob lives in the content-addressable `asset_content`
 * store, keyed by `(scope, content_hash)`; non-sensitive assets scope to 'global'.
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
        // Non-sensitive asset → dedup scope is 'global'; the pointer is on the row.
        val scope = "global"
        val contentHash =
            jdbi.withHandle<String, Exception> { h ->
                h.createQuery("SELECT content_hash FROM assets WHERE id = :id")
                    .bind("id", assetKey.value)
                    .mapTo(String::class.java)
                    .one()
            }

        val backup = withMediator { BuildTenantBackup(tenant.id).execute()!! }
        // At least our uploaded blob; the tenant also subscribes to the shared
        // `system` catalog, whose seeded badge asset contributes its own blob.
        // The byte-for-byte restore below is the real check.
        assertThat(backup.blobCount).isGreaterThanOrEqualTo(1)

        // Diverge: wipe the stored bytes, then restore them from the backup.
        jdbi.useHandle<Exception> {
            it.createUpdate("DELETE FROM asset_content WHERE scope = :s AND content_hash = :h")
                .bind("s", scope)
                .bind("h", contentHash)
                .execute()
        }
        withMediator { RestoreTenantBackup(tenant.id, backup.bytes).execute() }

        val restored =
            jdbi.withHandle<ByteArray?, Exception> { h ->
                h
                    .createQuery("SELECT content FROM asset_content WHERE scope = :s AND content_hash = :h")
                    .bind("s", scope)
                    .bind("h", contentHash)
                    .map { rs, _ -> rs.getBytes("content") }
                    .findOne()
                    .orElse(null)
            }
        assertThat(restored).isEqualTo(bytes)
    }
}

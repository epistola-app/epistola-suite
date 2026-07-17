package app.epistola.suite.storage.backfill

import app.epistola.suite.assets.GLOBAL_ASSET_SCOPE
import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.fonts.model.sha256Hex
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.storage.AssetContentStore
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The one-time backfill migrates a legacy `content_store` asset blob (the pre-#738
 * state: bytes keyed by `assets/{tenant}/{id}`, `assets.content_hash` still NULL) into
 * the content-addressable `asset_content`, stamping the pointer — and is idempotent.
 */
class ContentBackfillRunnerIT : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var runner: ContentBackfillRunner

    @Autowired
    private lateinit var assetContentStore: AssetContentStore

    @Test
    fun `backfills a legacy asset blob into asset_content and is idempotent`() {
        val tenant = createTenant("Backfill")
        val cat = CatalogKey.of("main")
        withMediator { CreateCatalog(tenantKey = tenant.id, id = cat, name = "Main").execute() }

        val assetId = AssetKey.generate()
        val bytes = ByteArray(128) { (it % 256).toByte() }
        val expectedHash = sha256Hex(bytes)

        // Simulate pre-migration state: asset row with NULL content_hash + a legacy
        // content_store blob at the old identity key.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO assets (id, tenant_key, catalog_key, name, media_type, size_bytes, width, height, content_hash, created_at)
                VALUES (:id, :tenant, :cat, 'legacy.png', 'image/png', :size, 1, 1, NULL, now())
                """,
            )
                .bind("id", assetId.value)
                .bind("tenant", tenant.id)
                .bind("cat", cat)
                .bind("size", bytes.size.toLong())
                .execute()
            handle.createUpdate(
                """
                INSERT INTO content_store (key, content, content_type, size_bytes, created_at)
                VALUES (:key, :bytes, 'image/png', :size, now())
                """,
            )
                .bind("key", "assets/${tenant.id.value}/${assetId.value}")
                .bind("bytes", bytes)
                .bind("size", bytes.size.toLong())
                .execute()
            // The real runner already ran (and set the completion marker) at context
            // startup; clear it so this manual invocation actually does the work.
            handle.createUpdate("DELETE FROM app_metadata WHERE key = 'content-backfill.completed'").execute()
        }

        runner.run()

        // Blob present in the CAS store (via the port) and served through the query…
        assertThat(assetContentStore.exists(GLOBAL_ASSET_SCOPE, expectedHash)).isTrue()
        assertThat(withMediator { GetAssetContent(tenant.id, assetId, cat).query() }!!.content).isEqualTo(bytes)
        // …and the pointer was actually stamped (not served via the legacy fallback, which
        // is still present in this test). No query surfaces content_hash, so read it directly.
        assertThat(contentHash(assetId)).isEqualTo(expectedHash)
        // A full pass records the completion marker (so later boots skip the runner).
        assertThat(completionMarker()).isNotNull()

        // Idempotent: a second run changes nothing (now short-circuited by the marker).
        runner.run()
        assertThat(contentHash(assetId)).isEqualTo(expectedHash)
        assertThat(assetContentStore.exists(GLOBAL_ASSET_SCOPE, expectedHash)).isTrue()
    }

    // Raw SQL: no query surfaces an asset's content_hash pointer or the internal
    // app_metadata backfill marker, so these two read them directly ("no other way").
    private fun contentHash(assetId: AssetKey): String? = jdbi.withHandle<String?, Exception> { handle ->
        handle.createQuery("SELECT content_hash FROM assets WHERE id = :id")
            .bind("id", assetId.value)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null)
    }

    private fun completionMarker(): String? = jdbi.withHandle<String?, Exception> { handle ->
        handle.createQuery("SELECT value::text FROM app_metadata WHERE key = 'content-backfill.completed'")
            .mapTo(String::class.java)
            .findOne()
            .orElse(null)
    }
}

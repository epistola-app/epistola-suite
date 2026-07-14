package app.epistola.suite.storage

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.DeleteAsset
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Content-addressable asset store behaviour (#738): dedup within a scope, scope
 * isolation across tenants, ref-aware deletion, and reaper mark-and-sweep.
 */
class AssetContentStoreIT : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var reaper: ContentReaper

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    private val bytes = ByteArray(256) { (it % 256).toByte() }

    @Test
    fun `identical bytes are stored once within a tenant, two assets point at one blob`(): Unit = withMediator {
        val tenant = createTenant("Dedup")
        val cat = CatalogKey.of("main")
        createCatalogFor(tenant.id, cat)

        val a1 = upload(tenant.id, cat, "one.png")
        val a2 = upload(tenant.id, cat, "two.png")

        val hash1 = contentHashOf(a1)
        val hash2 = contentHashOf(a2)
        assertThat(hash1).isEqualTo(hash2)
        // Exactly one blob for (scope=tenant, hash), referenced by both assets.
        assertThat(blobRows(tenant.id.value, hash1)).isEqualTo(1)
        assertThat(GetAssetContent(tenant.id, a1).query()!!.content).isEqualTo(bytes)
        assertThat(GetAssetContent(tenant.id, a2).query()!!.content).isEqualTo(bytes)
    }

    @Test
    fun `identical bytes across tenants are NOT deduplicated - separate scopes`(): Unit = withMediator {
        val a = createTenant("Tenant A")
        val b = createTenant("Tenant B")
        val cat = CatalogKey.of("main")
        createCatalogFor(a.id, cat)
        createCatalogFor(b.id, cat)

        val assetA = upload(a.id, cat, "logo.png")
        val assetB = upload(b.id, cat, "logo.png")
        val hash = contentHashOf(assetA)
        assertThat(contentHashOf(assetB)).isEqualTo(hash)

        // Same hash, but one blob per tenant scope — no cross-tenant existence leak.
        assertThat(blobRows(a.id.value, hash)).isEqualTo(1)
        assertThat(blobRows(b.id.value, hash)).isEqualTo(1)
    }

    @Test
    fun `deleting one of two assets sharing a blob keeps it, reaper GCs once unreferenced`(): Unit = withMediator {
        val tenant = createTenant("Shared Blob")
        val cat = CatalogKey.of("main")
        createCatalogFor(tenant.id, cat)

        val a1 = upload(tenant.id, cat, "a.png")
        val a2 = upload(tenant.id, cat, "b.png")
        val hash = contentHashOf(a1)

        DeleteAsset(tenant.id, a1).execute()
        // a2 still references the blob → present and served.
        assertThat(blobRows(tenant.id.value, hash)).isEqualTo(1)
        assertThat(GetAssetContent(tenant.id, a2).query()!!.content).isEqualTo(bytes)

        DeleteAsset(tenant.id, a2).execute()
        // Now unreferenced but within the reaper's grace window → still present.
        assertThat(blobRows(tenant.id.value, hash)).isEqualTo(1)

        // Backdate well before the (frozen test) clock's grace cutoff so the reaper —
        // which computes its cutoff from EpistolaClock, not DB now() — treats it as aged.
        backdateBlob(tenant.id.value, hash)
        reaper.reap()

        assertThat(blobRows(tenant.id.value, hash))
            .`as`("reaper should mark-and-sweep the now-unreferenced blob")
            .isEqualTo(0)
        // Gauge is published by the reaper (global count; other parallel tests may add
        // within-grace orphans, so only assert it's a finite non-negative value).
        assertThat(orphanGauge()).isGreaterThanOrEqualTo(0.0)
    }

    private fun createCatalogFor(tenant: TenantKey, cat: CatalogKey) {
        app.epistola.suite.catalog.commands.CreateCatalog(tenantKey = tenant, id = cat, name = cat.value).execute()
    }

    private fun upload(tenant: TenantKey, cat: CatalogKey, name: String) = UploadAsset(
        tenantId = tenant,
        name = name,
        mediaType = AssetMediaType.PNG,
        content = bytes,
        width = 1,
        height = 1,
        catalogKey = cat,
    ).execute().id

    private fun contentHashOf(assetId: app.epistola.suite.common.ids.AssetKey): String = jdbi.withHandle<String, Exception> { handle ->
        handle.createQuery("SELECT content_hash FROM assets WHERE id = :id")
            .bind("id", assetId.value)
            .mapTo(String::class.java)
            .one()
    }

    private fun blobRows(scope: String, hash: String): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery("SELECT count(*) FROM asset_content WHERE scope = :s AND content_hash = :h")
            .bind("s", scope)
            .bind("h", hash)
            .mapTo(Int::class.java)
            .one()
    }

    private fun backdateBlob(scope: String, hash: String) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate("UPDATE asset_content SET created_at = now() - interval '5 years' WHERE scope = :s AND content_hash = :h")
            .bind("s", scope)
            .bind("h", hash)
            .execute()
    }

    private fun orphanGauge(): Double = meterRegistry.get("epistola.storage.orphaned_blobs").tag("namespace", "asset").gauge().value()
}

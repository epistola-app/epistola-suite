package app.epistola.suite.storage

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.GLOBAL_ASSET_SCOPE
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

    @Test
    fun `identical bytes are stored once within a tenant, two assets point at one blob`(): Unit = withMediator {
        val tenant = createTenant("Dedup")
        val cat = CatalogKey.of("main")
        createCatalogFor(tenant.id, cat)
        val content = uniqueContent()

        val a1 = upload(tenant.id, cat, "one.png", content = content)
        val a2 = upload(tenant.id, cat, "two.png", content = content)

        val hash1 = contentHashOf(a1)
        val hash2 = contentHashOf(a2)
        assertThat(hash1).isEqualTo(hash2)
        // Exactly one blob for (scope=global, hash), referenced by both assets.
        assertThat(blobRows(GLOBAL_ASSET_SCOPE, hash1)).isEqualTo(1)
        assertThat(GetAssetContent(tenant.id, a1).query()!!.content).isEqualTo(content)
        assertThat(GetAssetContent(tenant.id, a2).query()!!.content).isEqualTo(content)
    }

    @Test
    fun `identical non-sensitive bytes dedup globally across tenants`(): Unit = withMediator {
        val a = createTenant("Tenant A")
        val b = createTenant("Tenant B")
        val cat = CatalogKey.of("main")
        createCatalogFor(a.id, cat)
        createCatalogFor(b.id, cat)
        val content = uniqueContent()

        val assetA = upload(a.id, cat, "logo.png", content = content)
        val assetB = upload(b.id, cat, "logo.png", content = content)
        val hash = contentHashOf(assetA)
        assertThat(contentHashOf(assetB)).isEqualTo(hash)

        // Non-sensitive → both tenants share ONE global blob (max dedup).
        assertThat(blobRows(GLOBAL_ASSET_SCOPE, hash)).isEqualTo(1)
        assertThat(GetAssetContent(a.id, assetA).query()!!.content).isEqualTo(content)
        assertThat(GetAssetContent(b.id, assetB).query()!!.content).isEqualTo(content)
    }

    @Test
    fun `sensitive assets are isolated per tenant, not shared globally`(): Unit = withMediator {
        val a = createTenant("Sensitive A")
        val b = createTenant("Sensitive B")
        val cat = CatalogKey.of("main")
        createCatalogFor(a.id, cat)
        createCatalogFor(b.id, cat)

        // Uploaded sensitively by two tenants and twice within tenant A.
        val secret = uniqueContent()
        val a1 = upload(a.id, cat, "secret1.png", sensitive = true, content = secret)
        val a2 = upload(a.id, cat, "secret2.png", sensitive = true, content = secret)
        val b1 = upload(b.id, cat, "secret.png", sensitive = true, content = secret)
        val hash = contentHashOf(a1)
        assertThat(contentHashOf(a2)).isEqualTo(hash)
        assertThat(contentHashOf(b1)).isEqualTo(hash)

        // Isolated per tenant: one blob in A's scope (shared by a1+a2), one in B's — and
        // nothing in the global scope, so there is no cross-tenant existence signal.
        assertThat(blobRows(a.id.value, hash)).isEqualTo(1)
        assertThat(blobRows(b.id.value, hash)).isEqualTo(1)
        assertThat(blobRows(GLOBAL_ASSET_SCOPE, hash)).isEqualTo(0)
        assertThat(GetAssetContent(a.id, a1).query()!!.content).isEqualTo(secret)
        assertThat(GetAssetContent(b.id, b1).query()!!.content).isEqualTo(secret)
    }

    @Test
    fun `deleting one of two assets sharing a blob keeps it, reaper GCs once unreferenced`(): Unit = withMediator {
        val tenant = createTenant("Shared Blob")
        val cat = CatalogKey.of("main")
        createCatalogFor(tenant.id, cat)
        val content = uniqueContent()

        val a1 = upload(tenant.id, cat, "a.png", content = content)
        val a2 = upload(tenant.id, cat, "b.png", content = content)
        val hash = contentHashOf(a1)

        DeleteAsset(tenant.id, a1).execute()
        // a2 still references the blob → present and served.
        assertThat(blobRows(GLOBAL_ASSET_SCOPE, hash)).isEqualTo(1)
        assertThat(GetAssetContent(tenant.id, a2).query()!!.content).isEqualTo(content)

        DeleteAsset(tenant.id, a2).execute()
        // Now unreferenced but within the reaper's grace window → still present.
        assertThat(blobRows(GLOBAL_ASSET_SCOPE, hash)).isEqualTo(1)

        // Backdate well before the (frozen test) clock's grace cutoff so the reaper —
        // which computes its cutoff from EpistolaClock, not DB now() — treats it as aged.
        backdateBlob(GLOBAL_ASSET_SCOPE, hash)
        reaper.reap()

        assertThat(blobRows(GLOBAL_ASSET_SCOPE, hash))
            .`as`("reaper should mark-and-sweep the now-unreferenced blob")
            .isEqualTo(0)
        // Gauge is published by the reaper (global count; other parallel tests may add
        // within-grace orphans, so only assert it's a finite non-negative value).
        assertThat(orphanGauge()).isGreaterThanOrEqualTo(0.0)
    }

    @Test
    fun `system-catalog assets dedup globally across tenants`(): Unit = withMediator {
        // The bundled system catalog (installed per tenant) ships the system-badge asset.
        // Bundled assets are non-sensitive → the global scope → two tenants share ONE
        // asset_content blob per bundled hash — the headline dedup win, and this feature's
        // demonstration on the bundled catalog (issue #738, rule #13).
        val a = createTenant("Sys A")
        val b = createTenant("Sys B")

        val hashesA = systemAssetHashes(a.id)
        val hashesB = systemAssetHashes(b.id)
        assertThat(hashesA).isNotEmpty
        assertThat(hashesB).containsExactlyInAnyOrderElementsOf(hashesA)
        // Each bundled hash is stored exactly once in the global scope, regardless of tenant count.
        hashesA.forEach { hash -> assertThat(blobRows(GLOBAL_ASSET_SCOPE, hash)).isEqualTo(1) }
    }

    private fun systemAssetHashes(tenant: TenantKey): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        handle.createQuery("SELECT content_hash FROM assets WHERE tenant_key = :t AND catalog_key = 'system' AND content_hash IS NOT NULL")
            .bind("t", tenant)
            .mapTo(String::class.java)
            .list()
    }

    private fun createCatalogFor(tenant: TenantKey, cat: CatalogKey) {
        app.epistola.suite.catalog.commands.CreateCatalog(tenantKey = tenant, id = cat, name = cat.value).execute()
    }

    /**
     * Fresh, globally-unique bytes per call — a random UUID's bytes. Guarantees the SHA-256
     * can't collide with any other test's blob in the shared DB (the frozen test clock rules
     * out a timestamp), so exact `blobRows(...)` assertions can never flake.
     */
    private fun uniqueContent(): ByteArray = java.util.UUID.randomUUID().toString().toByteArray(Charsets.UTF_8)

    private fun upload(tenant: TenantKey, cat: CatalogKey, name: String, sensitive: Boolean = false, content: ByteArray) = UploadAsset(
        tenantId = tenant,
        name = name,
        mediaType = AssetMediaType.PNG,
        content = content,
        width = 1,
        height = 1,
        catalogKey = cat,
        sensitive = sensitive,
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

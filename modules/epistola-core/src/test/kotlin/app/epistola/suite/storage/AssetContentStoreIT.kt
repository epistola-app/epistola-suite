// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.storage

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.DeleteAsset
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.sha256Hex
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Content-addressable asset store behaviour (#738): bytes stored once per installation, ref-aware
 * deletion, and the content collector's sweep of bytes nothing holds.
 *
 * Blob presence is checked through the [AssetContentStore] port (`exists`), and the
 * expected content hash is computed from the uploaded bytes ([sha256Hex]) — the same
 * derivation the write path uses — so the tests assert against the real API rather than
 * poking `asset_content` with SQL. `content_hash` is the table's PK, so
 * `exists` == "exactly one blob".
 */
class AssetContentStoreIT : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var assetContentStore: AssetContentStore

    @Autowired
    private lateinit var collector: app.epistola.suite.catalog.revisions.ResourceContentCollector

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

        // One global blob, both assets resolve to it.
        assertThat(assetContentStore.exists(sha256Hex(content))).isTrue()
        assertThat(GetAssetContent(tenant.id, a1).query()!!.content).isEqualTo(content)
        assertThat(GetAssetContent(tenant.id, a2).query()!!.content).isEqualTo(content)
    }

    @Test
    fun `identical bytes dedup across tenants`(): Unit = withMediator {
        val a = createTenant("Tenant A")
        val b = createTenant("Tenant B")
        val cat = CatalogKey.of("main")
        createCatalogFor(a.id, cat)
        createCatalogFor(b.id, cat)
        val content = uniqueContent()

        val assetA = upload(a.id, cat, "logo.png", content = content)
        val assetB = upload(b.id, cat, "logo.png", content = content)

        // Both tenants share ONE blob.
        assertThat(assetContentStore.exists(sha256Hex(content))).isTrue()
        assertThat(GetAssetContent(a.id, assetA).query()!!.content).isEqualTo(content)
        assertThat(GetAssetContent(b.id, assetB).query()!!.content).isEqualTo(content)
    }

    @Test
    fun `deleting one of two assets sharing a blob keeps it, the collector takes it once unreferenced`(): Unit = withMediator {
        val tenant = createTenant("Shared Blob")
        val cat = CatalogKey.of("main")
        createCatalogFor(tenant.id, cat)
        val content = uniqueContent()
        val hash = sha256Hex(content)

        val a1 = upload(tenant.id, cat, "a.png", content = content)
        val a2 = upload(tenant.id, cat, "b.png", content = content)

        DeleteAsset(tenant.id, a1).execute()
        // a2 still references the blob → present and served.
        assertThat(assetContentStore.exists(hash)).isTrue()
        assertThat(GetAssetContent(tenant.id, a2).query()!!.content).isEqualTo(content)

        DeleteAsset(tenant.id, a2).execute()
        // Now unreferenced but within the grace period → still present.
        assertThat(assetContentStore.exists(hash)).isTrue()
        // An image's content holds its bytes (ADR 0033): the content collector takes the deleted
        // images' contents first. Raw SQL: aging them past the grace period, which no command sets.
        jdbi.useHandle<Exception> { it.createUpdate("UPDATE resource_contents SET stored_at = NOW() - INTERVAL '5 years' WHERE tenant_key = :t AND kind = 'image'").bind("t", tenant.id).execute() }
        // Raw SQL: planting a historical timestamp the sweep asserts against; no command sets
        // asset_content.created_at.
        jdbi.backdateAssetBlob(hash)
        collector.collectAll()

        assertThat(assetContentStore.exists(hash))
            .`as`("the collector takes the now-unreferenced blob with its content")
            .isFalse()
        // The gauge counts what the sweep should have taken and did not, so a run that swept
        // everything reads zero. Blobs other tests leave inside the grace window are not orphans
        // and no longer inflate it.
        assertThat(orphanGauge()).isZero()
    }

    /**
     * Writing bytes that are stored already touches them (ADR 0033): an upload of an old,
     * unreferenced blob's bytes is about to point at it, so the sweep must not take it in between.
     */
    @Test
    fun `writing bytes that are stored already restarts their grace period`() {
        val content = uniqueContent()
        val hash = sha256Hex(content)
        assetContentStore.putIfAbsent(hash, content, "image/png", content.size.toLong())
        jdbi.backdateAssetBlob(hash)

        assetContentStore.putIfAbsent(hash, content, "image/png", content.size.toLong())
        collector.collectAll()

        assertThat(assetContentStore.exists(hash)).`as`("written again just now").isTrue()
    }

    @Test
    fun `system-catalog assets dedup globally across tenants`(): Unit = withMediator {
        // The bundled system catalog (installed per tenant) ships the system-badge asset.
        // Every asset's bytes are stored once per installation → two tenants share ONE
        // asset_content blob per bundled hash — the headline dedup win, and this feature's
        // demonstration on the bundled catalog (issue #738, rule #13).
        val a = createTenant("Sys A")
        val b = createTenant("Sys B")

        val hashesA = systemAssetHashes(a.id)
        val hashesB = systemAssetHashes(b.id)
        assertThat(hashesA).isNotEmpty
        assertThat(hashesB).containsExactlyInAnyOrderElementsOf(hashesA)
        // Each bundled hash is stored exactly once, regardless of tenant count.
        hashesA.forEach { hash -> assertThat(assetContentStore.exists(hash)).isTrue() }
    }

    /**
     * Content hashes of a tenant's installed system-catalog assets. Raw SQL: the bundled
     * system assets have no create command in the test, and no query surfaces an asset's
     * `content_hash`, so this reads it directly (the "no other way" exception).
     */
    private fun systemAssetHashes(tenant: TenantKey): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        handle.createQuery("SELECT content_hash FROM current_images WHERE tenant_key = :t AND catalog_id = (SELECT catalog_id FROM catalogs WHERE tenant_key = :t AND catalog_key = 'system') AND content_hash IS NOT NULL")
            .bind("t", tenant)
            .mapTo(String::class.java)
            .list()
    }

    private fun createCatalogFor(tenant: TenantKey, cat: CatalogKey) {
        CreateCatalog(tenantKey = tenant, id = cat, name = cat.value).execute()
    }

    /**
     * Fresh, globally-unique bytes per call — a random UUID's bytes. Guarantees the SHA-256
     * can't collide with any other test's blob in the shared DB (the frozen test clock rules
     * out a timestamp), so the `exists(...)` assertions can never flake.
     */
    private fun uniqueContent(): ByteArray = java.util.UUID.randomUUID().toString().toByteArray(Charsets.UTF_8)

    private fun upload(tenant: TenantKey, cat: CatalogKey, name: String, content: ByteArray) = UploadAsset(
        catalog = testCatalog(tenant, cat),
        name = name,
        mediaType = AssetMediaType.PNG,
        content = content,
        width = 1,
        height = 1,
    ).execute().id

    private fun orphanGauge(): Double = meterRegistry.get("epistola.storage.orphaned_blobs").tag("namespace", "asset").gauge().value()
}

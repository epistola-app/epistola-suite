// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.catalog.protocol.ImageResource
import app.epistola.catalog.protocol.ThemeResource
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.CatalogContent
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.storage.AssetContentStore
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * The content collector (ADR 0033): content nothing points at goes once its grace period is over,
 * with its binaries and references; content a release points at, content inside the grace period and
 * content a save holds stay; bytes an asset or another content holds stay.
 *
 * Orphans are made as an editor save will make them: content stored ([ResourceContentStore.retain])
 * that no release points at. Their age is set with SQL, since only the database clock writes it.
 */
class ResourceContentCollectorTest : IntegrationTestBase() {
    @Autowired private lateinit var jdbi: Jdbi

    @Autowired private lateinit var store: ResourceContentStore

    @Autowired private lateinit var assembler: ReleaseContentAssembler

    @Autowired private lateinit var blobs: AssetContentStore

    private val letters = CatalogKey.of("letters")

    private fun collector() = ResourceContentCollector(jdbi, StorageGcProperties(grace = Duration.ofDays(1), batchSize = 2, pause = Duration.ZERO), SimpleMeterRegistry())

    /** A tenant whose `letters` 1.0.0 holds theme `brand` and image `logo`. */
    private fun released(name: String): CatalogId {
        val tenant = createTenant(name).id
        withMediator {
            CreateCatalog(tenant, letters, "Letters").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), testCatalog(tenant, letters)), "Brand").execute()
            UploadAsset(testCatalog(tenant, letters), "Logo", AssetMediaType.PNG, png(0x123456), 1, 1, id = AssetKey.of("logo")).execute()
            mediator.releaseNext(testCatalog(TenantId(tenant), letters))
        }
        return testCatalog(tenant, letters)
    }

    /** The release's content with the theme renamed: stored, as a save stores it, and pointed at by nothing. */
    private fun orphan(catalog: CatalogId, themeName: String): String {
        val content = requireNotNull(assembler.assemble(catalog, "1.0.0")).content
        val changed: CatalogContent = content.copy(
            resourceDetails = content.resourceDetails.filterKeys { it == "theme/brand" }.mapValues { (_, detail) -> detail.copy(resource = (detail.resource as ThemeResource).copy(name = themeName)) },
        )
        return jdbi.inTransaction<String, Exception> { store.retain(it, catalog.tenantKey, changed).getValue("theme/brand") }
    }

    private fun age(tenant: TenantKey, hash: String, days: Long) = jdbi.useHandle<Exception> {
        it.createUpdate("UPDATE resource_contents SET stored_at = NOW() - CAST(:age AS INTERVAL) WHERE tenant_key = :t AND content_hash = :h")
            .bind("age", "$days days").bind("t", tenant).bind("h", hash).execute()
    }

    private fun exists(tenant: TenantKey, hash: String): Boolean = jdbi.withHandle<Boolean, Exception> {
        it.createQuery("SELECT EXISTS (SELECT 1 FROM resource_contents WHERE tenant_key = :t AND content_hash = :h)").bind("t", tenant).bind("h", hash).mapTo(Boolean::class.java).one()
    }

    private fun released(catalog: CatalogId, type: String): String = jdbi.withHandle<String, Exception> {
        it.createQuery("SELECT content_hash FROM release_resources WHERE tenant_key = :t AND version IS NOT NULL AND resource_type = :type AND catalog_id = (SELECT catalog_id FROM catalogs WHERE tenant_key = :t AND catalog_key = 'letters')").bind("t", catalog.tenantKey).bind("type", type).mapTo(String::class.java).one()
    }

    @Test
    fun `an orphan past the grace period goes, a referenced content and one inside the grace period stay`() {
        val catalog = released("gc-basics")
        val tenant = catalog.tenantKey
        val old = orphan(catalog, "Old orphan")
        val fresh = orphan(catalog, "Fresh orphan")
        val held = released(catalog, "theme")
        age(tenant, old, 3)
        age(tenant, held, 3)
        val collector = collector()
        assertThat(collector.countOrphans(tenant)).isEqualTo(1)

        val collected = collector.collect(tenant)

        assertThat(collected.contents).isEqualTo(1)
        assertThat(exists(tenant, old)).isFalse()
        assertThat(exists(tenant, held)).`as`("a release points at it").isTrue()
        assertThat(exists(tenant, fresh)).`as`("inside the grace period").isTrue()
        assertThat(collector.countOrphans(tenant)).isZero()
    }

    @Test
    fun `content a release points at cannot be deleted, whatever query tries`() {
        val catalog = released("gc-foreign-key")
        val held = released(catalog, "theme")

        assertThatThrownBy {
            jdbi.useTransaction<Exception> { it.createUpdate("DELETE FROM resource_contents WHERE tenant_key = :t AND content_hash = :h").bind("t", catalog.tenantKey).bind("h", held).execute() }
        }.`as`("refused when the transaction commits: the foreign key is deferred").rootCause().hasMessageContaining("fk_release_resources_content")
    }

    @Test
    fun `a save that stores an old orphan again holds it, and the collector skips it`() {
        val catalog = released("gc-concurrent-save")
        val tenant = catalog.tenantKey
        val reused = orphan(catalog, "Reused")
        age(tenant, reused, 3)
        val executor = Executors.newSingleThreadExecutor()
        try {
            jdbi.useTransaction<Exception> { save ->
                // The same content stored again inside an open save: it touches and locks the row.
                assertThat(orphanIn(save, catalog, "Reused")).isEqualTo(reused)
                val whileHeld = executor.submit<ContentCollection> { collector().collect(tenant) }.get()
                assertThat(whileHeld.contents).`as`("the locked row is skipped").isZero()
            }
            assertThat(exists(tenant, reused)).isTrue()
            assertThat(executor.submit<ContentCollection> { collector().collect(tenant) }.get().contents).`as`("stored again, inside the grace period").isZero()
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `bytes an asset or a released content holds stay, bytes nothing holds go with their content`() {
        val catalog = released("gc-blobs")
        val tenant = catalog.tenantKey
        val logo = requireNotNull(assembler.assemble(catalog, "1.0.0")).content.resourceDetails.getValue("image/logo").resource as ImageResource
        // An image only an orphaned content needs: its bytes are held by that content alone.
        val loose = png(0x654321)
        val looseHash = app.epistola.suite.fonts.model.sha256Hex(loose)
        blobs.putIfAbsent(looseHash, loose, "image/png", loose.size.toLong())
        val content = requireNotNull(assembler.assemble(catalog, "1.0.0")).content
        val orphanHash = jdbi.inTransaction<String, Exception> { handle ->
            store.retain(
                handle,
                tenant,
                content.copy(
                    resourceDetails = mapOf("image/loose" to content.resourceDetails.getValue("image/logo").let { it.copy(resource = logo.copy(key = "loose", contentHash = looseHash)) }),
                    assetContents = mapOf(logo.copy(contentHash = looseHash).contentPath() to loose),
                ),
            ).getValue("image/loose")
        }
        age(tenant, orphanHash, 3)
        jdbi.useHandle<Exception> { it.createUpdate("UPDATE asset_content SET created_at = NOW() - INTERVAL '3 days' WHERE content_hash IN (:a, :b)").bind("a", looseHash).bind("b", logo.contentHash).execute() }

        val collected = collector().collect(tenant)

        assertThat(collected.contents).isEqualTo(1)
        assertThat(blobs.get(looseHash)).`as`("nothing holds it once its content went").isNull()
        assertThat(blobs.get(logo.contentHash)).`as`("an asset and the release hold it").isNotNull()
    }

    private fun orphanIn(handle: org.jdbi.v3.core.Handle, catalog: CatalogId, themeName: String): String {
        val content = requireNotNull(assembler.assemble(catalog, "1.0.0")).content
        val changed = content.copy(resourceDetails = content.resourceDetails.filterKeys { it == "theme/brand" }.mapValues { (_, detail) -> detail.copy(resource = (detail.resource as ThemeResource).copy(name = themeName)) })
        return store.retain(handle, catalog.tenantKey, changed).getValue("theme/brand")
    }

    private fun png(rgb: Int): ByteArray = ByteArrayOutputStream().use { out ->
        ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, rgb) }, "png", out)
        out.toByteArray()
    }
}

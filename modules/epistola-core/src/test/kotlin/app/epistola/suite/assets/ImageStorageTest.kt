// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets

import app.epistola.suite.assets.commands.DeleteAsset
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.assets.queries.GetAsset
import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.assets.queries.ListImagePage
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.system.systemCatalog
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.ImageStorageConversion
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * A draft's images are stored as JSON (ADR 0033, step 6): `{ "resource": <ImageResource> }` named by
 * a draft row, its bytes in the one store, once per installation, linked from the content. There is
 * no `sensitive` scope any more (#751): every image's bytes are deduplicated alike.
 */
class ImageStorageTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    private fun one(sql: String): String = jdbi.withHandle<String, Exception> { it.createQuery(sql).mapTo(String::class.java).one() }

    private fun png(rgb: Int): ByteArray = ByteArrayOutputStream().use { out ->
        ImageIO.write(BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, rgb) }, "png", out)
        out.toByteArray()
    }

    @Test
    fun `an image is content naming its bytes, linked, and the tables are gone`() {
        val tenant = createTenant("Image storage").id
        val letters = testCatalog(tenant, CatalogKey.of("letters"))
        val bytes = png(0x123456)
        withMediator {
            CreateCatalog(tenant, letters.key, "Letters").execute()
            val created = UploadAsset(letters, "Logo", AssetMediaType.PNG, bytes, 2, 1, id = AssetKey.of("logo")).execute()
            assertThat(created).`as`("what an upload returns is what a read gives").isEqualTo(GetAsset(tenant, AssetKey.of("logo")).query())
            assertThat(created.sizeBytes).isEqualTo(bytes.size.toLong())
            assertThat(GetAssetContent(tenant, AssetKey.of("logo")).query()!!.content).isEqualTo(bytes)
            assertThat(ListImagePage(letters, limit = 10, offset = 0).query().items.map { it.id.value }).containsExactly("logo")
        }
        assertThat(
            one(
                """
                SELECT count(*) FROM release_resources r JOIN resource_binaries b ON b.tenant_key = r.tenant_key AND b.content_hash = r.content_hash
                WHERE r.tenant_key = '${tenant.value}' AND r.resource_type = 'image' AND r.version_id IS NOT NULL AND r.resource_key = 'logo'
                """,
            ),
        ).`as`("its content links to its bytes").isEqualTo("1")
        assertThat(one("SELECT CAST(to_regclass('assets') IS NULL AND to_regclass('content_store') IS NULL AS TEXT)")).isEqualTo("true")

        withMediator { DeleteAsset(tenant, AssetKey.of("logo")).execute() }
        assertThat(withMediator { GetAsset(tenant, AssetKey.of("logo")).query() }).isNull()
    }

    @Test
    fun `identical bytes uploaded by two tenants are stored once`() {
        val bytes = png(0x654321)
        for (name in listOf("Image dedup A", "Image dedup B")) {
            val tenant = createTenant(name).id
            val letters = testCatalog(tenant, CatalogKey.of("letters"))
            withMediator {
                CreateCatalog(tenant, letters.key, "Letters").execute()
                UploadAsset(letters, "Logo", AssetMediaType.PNG, bytes, 2, 1).execute()
            }
        }
        assertThat(one("SELECT count(*) FROM asset_content WHERE content_hash = '${app.epistola.suite.fonts.model.sha256Hex(bytes)}'")).isEqualTo("1")
    }

    @Test
    fun `an image reads the same from its draft and from the release it holds`() {
        val tenant = createTenant("Image read parity").id
        val letters = testCatalog(tenant, CatalogKey.of("letters"))
        val bytes = png(0x0000ff)
        withMediator {
            CreateCatalog(tenant, letters.key, "Letters").execute()
            UploadAsset(letters, "Logo", AssetMediaType.PNG, bytes, 2, 1, id = AssetKey.of("logo")).execute()
        }
        val version = withMediator { mediator.releaseNext(letters) }
        val released = CatalogReleaseRef(letters, DraftRef.Released(version))
        withMediator {
            fun content(page: List<Asset>) = page.map { listOf(it.id, it.name, it.mediaType, it.width, it.height, it.sizeBytes) }
            assertThat(content(ListImagePage(letters, limit = 10, offset = 0, catalogVersion = released).query().items))
                .isEqualTo(content(ListImagePage(letters, limit = 10, offset = 0).query().items))
            assertThat(GetAssetContent(tenant, AssetKey.of("logo"), catalog = letters, catalogVersion = released).query()!!.content).isEqualTo(bytes)
        }
    }

    @Test
    fun `the migration converts every system image and an uploaded one as the suite stores it`() {
        val tenant = createTenant("Image conversion").id
        val letters = testCatalog(tenant, CatalogKey.of("letters"))
        withMediator {
            CreateCatalog(tenant, letters.key, "Letters").execute()
            UploadAsset(letters, "Logo", AssetMediaType.PNG, png(0xff0000), 2, 1).execute()
            UploadAsset(letters, "Mark", AssetMediaType.SVG, """<svg xmlns="http://www.w3.org/2000/svg" width="4" height="4"/>""".toByteArray(), null, null).execute()
        }
        ImageStorageConversion.verify(jdbi, systemCatalog(tenant))
        ImageStorageConversion.verify(jdbi, letters)
    }
}

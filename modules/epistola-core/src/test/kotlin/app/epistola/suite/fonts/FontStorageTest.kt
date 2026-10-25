// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

import app.epistola.suite.assets.queries.ListImagePage
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.system.systemCatalog
import app.epistola.suite.common.ids.FontId
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.fonts.commands.DeleteFont
import app.epistola.suite.fonts.queries.GetFontVariantContent
import app.epistola.suite.fonts.queries.GetFontVariants
import app.epistola.suite.fonts.queries.ListFonts
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.FontFixtures
import app.epistola.suite.testing.FontStorageConversion
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ResourceLoader

/**
 * A draft's fonts are stored as JSON (ADR 0033, step 5): `{ "resource": <FontResource> }` named by a
 * draft row, each face's file a binary of the font (`resource_binaries`) in the shared store. No
 * asset row stands for a face, so the images list never shows one.
 */
class FontStorageTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var fonts: FontStore

    @Autowired
    private lateinit var resourceLoader: ResourceLoader

    private fun one(sql: String): String = jdbi.withHandle<String, Exception> { it.createQuery(sql).mapTo(String::class.java).one() }

    private fun bytes(file: String) = resourceLoader.getResource("classpath:epistola/fonts/lato/$file").contentAsByteArray

    @Test
    fun `a font is content whose faces are its binaries, with no asset row, and the tables are gone`() {
        val tenant = createTenant("Font storage").id
        val letters = testCatalog(tenant, CatalogKey.of("letters"))
        val regular = bytes("lato-Regular.ttf")
        withMediator {
            CreateCatalog(tenant, letters.key, "Letters").execute()
            FontFixtures.importFont(letters, "house-sans", "House Sans", mapOf((400 to false) to regular, (700 to false) to bytes("lato-Bold.ttf")))
        }
        val id = FontId(FontKey.of("house-sans"), letters)
        assertThat(withMediator { GetFontVariants(id).query() }.map { it.weight to it.italic }).containsExactly(400 to false, 700 to false)
        assertThat(withMediator { GetFontVariantContent(letters, id.key, 400, false).query() }).isEqualTo(regular)
        assertThat(
            one(
                """
                SELECT count(*) FROM release_resources r JOIN resource_binaries b ON b.tenant_key = r.tenant_key AND b.content_hash = r.content_hash
                WHERE r.tenant_key = '${tenant.value}' AND r.resource_type = 'font' AND r.version_id IS NOT NULL AND r.resource_key = 'house-sans'
                """,
            ),
        ).`as`("each face linked to its bytes").isEqualTo("2")
        assertThat(one("SELECT count(*) FROM current_images WHERE tenant_key = '${tenant.value}' AND media_type LIKE 'font/%'")).`as`("no image for a face").isEqualTo("0")
        assertThat(withMediator { ListImagePage(letters, limit = 50, offset = 0).query() }.items).isEmpty()
        assertThat(one("SELECT CAST(to_regclass('fonts') IS NULL AND to_regclass('font_variants') IS NULL AS TEXT)")).isEqualTo("true")

        withMediator { DeleteFont(id).execute() }
        assertThat(withMediator { GetFontVariantContent(letters, id.key, 400, false).query() }).`as`("deleted with its faces").isNull()
    }

    @Test
    fun `a font reads the same from its draft and from the release it holds`() {
        val tenant = createTenant("Font read parity").id
        val letters = testCatalog(tenant, CatalogKey.of("letters"))
        withMediator {
            CreateCatalog(tenant, letters.key, "Letters").execute()
            FontFixtures.importFont(letters, "house-sans", "House Sans", mapOf((400 to false) to bytes("lato-Regular.ttf")))
        }
        val version = withMediator { mediator.releaseNext(letters) }
        val released = CatalogReleaseRef(letters, DraftRef.Released(version))
        withMediator {
            assertThat(ListFonts(TenantId(tenant), catalog = letters, catalogVersion = released).query().map { it.slug to it.name })
                .isEqualTo(ListFonts(TenantId(tenant), catalog = letters).query().map { it.slug to it.name })
            val id = FontId(FontKey.of("house-sans"), letters)
            assertThat(GetFontVariants(id, catalogVersion = released).query()).isEqualTo(GetFontVariants(id).query())
        }
    }

    @Test
    fun `the migration converts every system font and an uploaded one as the suite stores it`() {
        val tenant = createTenant("Font conversion").id
        val letters = testCatalog(tenant, CatalogKey.of("letters"))
        withMediator {
            CreateCatalog(tenant, letters.key, "Letters").execute()
            FontFixtures.importFont(letters, "house-sans", "House Sans", mapOf((700 to true) to bytes("lato-BoldItalic.ttf"), (400 to false) to bytes("lato-Regular.ttf")))
        }
        FontStorageConversion.verify(jdbi, fonts, systemCatalog(tenant))
        FontStorageConversion.verify(jdbi, fonts, letters)
    }
}

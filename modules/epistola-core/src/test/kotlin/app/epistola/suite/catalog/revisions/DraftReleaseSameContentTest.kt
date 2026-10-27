// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRefOverride
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * A draft stores what a release of it stores (ADR 0033): released unchanged, every resource of a
 * type stored as JSON names the same content row from its draft row and its release row, so
 * identical content is stored once and a draft and its release read the same. References into
 * the resource's own catalog included, which a release names relatively.
 */
class DraftReleaseSameContentTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `a draft released unchanged names the same content as its release, for every type stored as JSON`() {
        val tenant = createTenant("Same content").id
        val letters = testCatalog(tenant, CatalogKey.of("letters"))
        withMediator {
            CreateCatalog(tenant, letters.key, "Letters").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), letters), "Brand", documentStyles = mapOf("fontFamily" to mapOf("key" to "inter", "catalogKey" to "system"))).execute()
            CreateCodeList(CodeListId(CodeListKey.of("regions"), letters), "Regions", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("eu", "Europe"))).execute()
            CreateAttributeDefinition(AttributeId(AttributeKey.of("region"), letters), "Region", codeListId = CodeListId(CodeListKey.of("regions"), letters)).execute()
            app.epistola.suite.testing.FontFixtures.importFont(
                letters,
                "house-sans",
                "House Sans",
                mapOf((400 to false) to javaClass.classLoader.getResourceAsStream("epistola/catalogs/system/binaries/fonts/lato/lato-Regular.ttf")!!.readBytes()),
            )
            app.epistola.suite.assets.commands.UploadAsset(
                letters,
                "Mark",
                app.epistola.suite.assets.AssetMediaType.SVG,
                """<svg xmlns="http://www.w3.org/2000/svg" width="4" height="4"/>""".toByteArray(),
                null,
                null,
                id = app.epistola.suite.common.ids.AssetKey.of("mark"),
            ).execute()
            // Names a theme of its own catalog: a release says that relatively.
            CreateStencil(
                StencilId(StencilKey.of("footer"), letters),
                "Footer",
                content = TemplateDocument(
                    modelVersion = 1,
                    root = "root",
                    nodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf("children"))),
                    slots = mapOf("children" to Slot(id = "children", nodeId = "root", name = "children", children = emptyList())),
                    themeRef = ThemeRefOverride(themeKey = "brand"),
                ),
            ).execute()
            // A template: its own theme by the editor's qualified name, a second variant described.
            val template = app.epistola.suite.common.ids.TemplateId(app.epistola.suite.common.ids.TemplateKey.of("invoice"), letters)
            app.epistola.suite.templates.commands.CreateDocumentTemplate(template, "Invoice").execute()
            app.epistola.suite.templates.commands.UpdateDocumentTemplate(template, themeId = ThemeKey.of("brand"), themeCatalog = letters).execute()
            app.epistola.suite.templates.commands.variants.CreateVariant(app.epistola.suite.common.ids.VariantId(app.epistola.suite.common.ids.VariantKey.of("second"), template), "Second", "For courts").execute()
            app.epistola.suite.templates.commands.variants.UpdateVariantContent(
                app.epistola.suite.common.ids.VariantId(app.epistola.suite.common.ids.VariantKey.INITIAL, template),
                TemplateDocument(
                    modelVersion = 1,
                    root = "root",
                    nodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf("children"))),
                    slots = mapOf("children" to Slot(id = "children", nodeId = "root", name = "children", children = emptyList())),
                    themeRef = ThemeRefOverride(themeKey = "brand", catalogKey = "letters"),
                ),
            ).execute()
        }
        val version = withMediator { mediator.releaseNext(letters) }

        val pairs = jdbi.withHandle<List<Triple<String, String, String>>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT d.resource_type || '/' || d.resource_key AS resource, d.content_hash AS drafted, r.content_hash AS released
                FROM release_resources d
                JOIN release_resources r ON r.tenant_key = d.tenant_key AND r.catalog_id = d.catalog_id
                  AND r.resource_type = d.resource_type AND r.resource_key = d.resource_key AND r.parent_id IS NOT DISTINCT FROM d.parent_id
                  AND r.version_id IN (SELECT rv.version_id FROM catalog_versions rv WHERE rv.state = 'released' AND rv.version = :v)
                WHERE d.tenant_key = :t AND release_resource_is_draft(d.tenant_key, d.version_id)
                  AND d.catalog_id = (SELECT catalog_id FROM catalogs WHERE tenant_key = :t AND catalog_key = 'letters')
                ORDER BY 1
                """,
            ).bind("t", tenant).bind("v", version)
                .map { rs, _ -> Triple(rs.getString("resource"), rs.getString("drafted"), rs.getString("released")) }
                .list()
        }
        assertThat(pairs.map { it.first }).containsExactly(
            "attribute/region", "codeList/regions", "font/house-sans", "image/mark", "stencil/footer", "template/invoice", "theme/brand",
            // A template's variants are rows of their own, each the same content in draft and release.
            "variant/initial", "variant/second",
        )
        assertThat(pairs).allSatisfy { (resource, drafted, released) -> assertThat(drafted).`as`(resource).isEqualTo(released) }
    }
}

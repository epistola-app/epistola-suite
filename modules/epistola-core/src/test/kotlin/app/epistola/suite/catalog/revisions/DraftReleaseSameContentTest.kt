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
                mapOf((400 to false) to javaClass.classLoader.getResourceAsStream("epistola/fonts/lato/lato-Regular.ttf")!!.readBytes()),
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
        }
        val version = withMediator { mediator.releaseNext(letters) }

        val pairs = jdbi.withHandle<List<Triple<String, String, String>>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT d.resource_type || '/' || d.resource_key AS resource, d.content_hash AS drafted, r.content_hash AS released
                FROM release_resources d
                JOIN release_resources r ON r.tenant_key = d.tenant_key AND r.catalog_id = d.catalog_id
                  AND r.resource_type = d.resource_type AND r.resource_key = d.resource_key AND r.version = :v
                WHERE d.tenant_key = :t AND d.version_id IS NOT NULL
                  AND d.catalog_id = (SELECT catalog_id FROM catalogs WHERE tenant_key = :t AND catalog_key = 'letters')
                ORDER BY 1
                """,
            ).bind("t", tenant).bind("v", version)
                .map { rs, _ -> Triple(rs.getString("resource"), rs.getString("drafted"), rs.getString("released")) }
                .list()
        }
        assertThat(pairs.map { it.first }).containsExactly("attribute/region", "codeList/regions", "font/house-sans", "image/mark", "stencil/footer", "theme/brand")
        assertThat(pairs).allSatisfy { (resource, drafted, released) -> assertThat(drafted).`as`(resource).isEqualTo(released) }
    }

    @Test
    fun `a template's draft models are its release's models, references into its own catalog included`() {
        val tenant = createTenant("Same template models").id
        val letters = testCatalog(tenant, CatalogKey.of("letters"))
        val template = app.epistola.suite.common.ids.TemplateId(app.epistola.suite.common.ids.TemplateKey.of("invoice"), letters)
        val default = app.epistola.suite.common.ids.VariantId(app.epistola.suite.common.ids.VariantKey.INITIAL, template)
        val second = app.epistola.suite.common.ids.VariantId(app.epistola.suite.common.ids.VariantKey.of("second"), template)
        // The editor sends a reference into the template's own catalog named with it.
        val model = TemplateDocument(
            modelVersion = 1,
            root = "root",
            nodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf("children"))),
            slots = mapOf("children" to Slot(id = "children", nodeId = "root", name = "children", children = emptyList())),
            themeRef = ThemeRefOverride(themeKey = "brand", catalogKey = "letters"),
        )
        withMediator {
            CreateCatalog(tenant, letters.key, "Letters").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), letters), "Brand").execute()
            app.epistola.suite.templates.commands.CreateDocumentTemplate(template, "Invoice").execute()
            app.epistola.suite.templates.commands.variants.CreateVariant(second, "Second", null).execute()
            app.epistola.suite.templates.commands.variants.UpdateVariantContent(default, model).execute()
            app.epistola.suite.templates.commands.variants.UpdateVariantContent(second, model).execute()
        }
        val version = withMediator { mediator.releaseNext(letters) }

        // Storage itself is what this holds, so it reads the rows: the draft's model per variant, and
        // the model its release lifted (the default variant's onto the template).
        val models = jdbi.withHandle<List<Triple<String, String?, String?>>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT v.id AS variant, v.template_model::text AS drafted,
                       (SELECT c.payload::text FROM resource_contents c
                        WHERE c.tenant_key = r.tenant_key
                          AND c.content_hash = COALESCE(
                                (SELECT e -> 'templateModel' ->> 'revisionDigest' FROM jsonb_array_elements(p.payload -> 'variants') e WHERE e ->> 'key' = v.id),
                                p.payload -> 'templateModel' ->> 'revisionDigest')) AS released
                FROM template_variants v
                JOIN document_templates t ON t.tenant_key = v.tenant_key AND t.version_id = v.version_id AND t.resource_id = v.template_resource_id
                JOIN release_resources r ON r.tenant_key = t.tenant_key AND r.resource_id = t.resource_id AND r.version = :v
                JOIN resource_contents p ON p.tenant_key = r.tenant_key AND p.content_hash = r.content_hash
                WHERE v.tenant_key = :t AND t.id = 'invoice' AND v.version_id = (SELECT version_id FROM catalog_versions WHERE tenant_key = :t AND catalog_id = t.catalog_id AND is_next)
                ORDER BY 1
                """,
            ).bind("t", tenant).bind("v", version)
                .map { rs, _ -> Triple(rs.getString("variant"), rs.getString("drafted"), rs.getString("released")) }
                .list()
        }
        val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        assertThat(models.map { it.first }).containsExactly("initial", "second")
        assertThat(models).allSatisfy { (variant, drafted, released) ->
            assertThat(released).`as`(variant).isNotNull()
            assertThat(mapper.readTree(drafted)).`as`(variant).isEqualTo(mapper.readTree(released))
        }
    }
}

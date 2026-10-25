// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Storing content records what it refers to (ADR 0033, `resource_references`): a template's theme,
 * the stencil its model embeds (on the model's own content), an attribute's code list.
 */
class ResourceReferencesTest : IntegrationTestBase() {
    @Autowired private lateinit var jdbi: Jdbi

    @Test
    fun `releasing records each content's references, as the content names them`() {
        val tenant = createTenant("Content references").id
        val letters = CatalogKey.of("letters")
        val catalog = withMediator {
            CreateCatalog(tenant, letters, "Letters").execute()
            testCatalog(tenant, letters)
        }
        withMediator {
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalog), "Brand").execute()
            CreateStencil(StencilId(StencilKey.of("letterhead"), catalog), "Letterhead").execute()
            val codeList = CodeListId(CodeListKey.of("regions"), catalog)
            CreateCodeList(id = codeList, displayName = "Regions", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("eu", "Europe"))).execute()
            CreateAttributeDefinition(id = AttributeId(AttributeKey.of("region"), catalog), displayName = "Region", codeListId = codeList).execute()
            val template = TemplateId(TemplateKey.of("invoice"), catalog)
            CreateDocumentTemplate(template, "Invoice").execute().withRequiredDataExample()
            UpdateDocumentTemplate(template, themeId = ThemeKey.of("brand"), themeCatalog = catalog).execute()
            UpdateVariantContent(VariantId(VariantKey.INITIAL, template), embedding("letterhead")).execute()
            MarkCatalogReady(catalog).execute()
            ReleaseCatalogDraft(catalog, "1.0.0").execute()
        }

        val references = jdbi.withHandle<List<String>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT c.kind || ' -> ' || COALESCE(r.alias, '(own)') || ' ' || r.resource_type || '/' || r.resource_key
                FROM resource_references r JOIN resource_contents c ON c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash
                WHERE r.tenant_key = :t AND r.resource_key IN ('brand', 'letterhead', 'regions')
                """,
            ).bind("t", tenant).mapTo(String::class.java).list()
        }
        assertThat(references).contains(
            // A template names a theme of its own catalog relatively, as its models do (ADR 0033).
            "template -> (own) theme/brand",
            "templateModel -> (own) stencil/letterhead",
            "attribute -> (own) codeList/regions",
        )
    }

    private fun embedding(stencilKey: String) = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "instance" to Node(id = "instance", type = "stencil", slots = listOf("instance-children"), props = mapOf("stencilKey" to stencilKey)),
        ),
        slots = mapOf(
            "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("instance")),
            "instance-children" to Slot(id = "instance-children", nodeId = "instance", name = "children", children = emptyList()),
        ),
        themeRef = ThemeRef.Inherit,
    )
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.queries.variants.GetVariantContent
import app.epistola.suite.testing.testAddress
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRefOverride
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A moved template keeps what its models mean, and stores them in its new catalog's form (ADR 0033):
 * a reference into the catalog it left is named with that catalog, one into the catalog it joined is
 * relative, as a release of it would hold it.
 */
class TemplateModelRelocationTest : RelocationTestSupport() {

    private fun model(themeKey: String, themeCatalog: String?) = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf("children"))),
        slots = mapOf("children" to Slot(id = "children", nodeId = "root", name = "children", children = emptyList())),
        themeRef = ThemeRefOverride(themeKey = themeKey, catalogKey = themeCatalog),
    )

    /** The variant's model as stored: its `themeRef` as the row holds it. */
    private fun storedThemeRef(tenant: app.epistola.suite.common.ids.TenantKey, templateResource: String): String = jdbi.withHandle<String, Exception> { handle ->
        handle.createQuery(
            "SELECT v.template_model -> 'themeRef' ->> 'catalogKey' FROM template_variants v JOIN catalog_resources r ON r.tenant_key = v.tenant_key AND r.resource_id = v.template_resource_id WHERE v.tenant_key = :t AND r.resource_type = 'template' AND r.resource_key = :k",
        ).bind("t", tenant).bind("k", templateResource).mapTo(String::class.java).findOne().orElse("<relative>")
    }

    @Test
    fun `a moved template's models keep their meaning and take the destination's form`() {
        val tenant = tenantWith("Template model relocation")
        val leaving = TemplateId(TemplateKey.of("leaving"), catalogId(tenant, letters))
        val joining = TemplateId(TemplateKey.of("joining"), catalogId(tenant, shared))
        withMediator {
            CreateTheme(ThemeId(ThemeKey.of("house"), catalogId(tenant, letters)), "House").execute()
            CreateDocumentTemplate(leaving, "Leaving").execute()
            CreateDocumentTemplate(joining, "Joining").execute()
            UpdateVariantContent(VariantId(VariantKey.INITIAL, leaving), model("house", null)).execute()
            UpdateVariantContent(VariantId(VariantKey.INITIAL, joining), model("house", "letters")).execute()
        }
        assertThat(storedThemeRef(tenant, "leaving")).isEqualTo("<relative>")
        assertThat(storedThemeRef(tenant, "joining")).isEqualTo("letters")

        move(tenant, testAddress(tenant, CatalogResourceType.TEMPLATE, letters.value, "leaving").movedTo(shared))
        move(tenant, testAddress(tenant, CatalogResourceType.TEMPLATE, shared.value, "joining").movedTo(letters))

        // Leaving letters, its theme is named with letters; joining it, its theme is its own.
        assertThat(storedThemeRef(tenant, "leaving")).isEqualTo("letters")
        assertThat(storedThemeRef(tenant, "joining")).isEqualTo("<relative>")
        withMediator {
            val left = GetVariantContent(VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("leaving"), catalogId(tenant, shared)))).query()
            val joined = GetVariantContent(VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("joining"), catalogId(tenant, letters)))).query()
            assertThat(left?.themeRef).isEqualTo(ThemeRefOverride(themeKey = "house", catalogKey = "letters"))
            assertThat(joined?.themeRef).isEqualTo(ThemeRefOverride(themeKey = "house", catalogKey = "letters"))
        }
    }
}

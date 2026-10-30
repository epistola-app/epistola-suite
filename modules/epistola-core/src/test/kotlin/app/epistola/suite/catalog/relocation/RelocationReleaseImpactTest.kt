// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.releases.ChangeKind
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What a refactoring means for releases, shown before it happens (ADR 0042): each catalog it changes
 * with the kind its next release becomes and why, and the order they are released in, a catalog
 * before those that now depend on it.
 */
class RelocationReleaseImpactTest : RelocationTestSupport() {

    @Test
    fun `moving a released theme out makes the source's next release major and releases the target first`() {
        val tenant = lettersWithHouse("impact-released", released = true)

        val plan = preview(tenant, address(tenant, CatalogResourceType.THEME, letters, "house").movedTo(shared))

        val byCatalog = plan.releases.associateBy { it.catalog.substringAfter('/') }
        assertThat(byCatalog.getValue("letters").kind).isEqualTo(ChangeKind.BREAKING)
        assertThat(byCatalog.getValue("letters").reasons).containsExactly("no longer contains theme 'house'", "now depends on shared")
        assertThat(byCatalog.getValue("shared").kind).isEqualTo(ChangeKind.ADDITIVE)
        assertThat(byCatalog.getValue("shared").reasons).containsExactly("gains theme 'house'")
        assertThat(plan.releaseOrder.map { it.substringAfter('/') }).containsExactly("shared", "letters")
    }

    @Test
    fun `moving a theme no release holds yet breaks nothing`() {
        val tenant = lettersWithHouse("impact-unreleased", released = false)

        val plan = preview(tenant, address(tenant, CatalogResourceType.THEME, letters, "house").movedTo(shared))

        val letters = plan.releases.single { it.catalog.endsWith("/letters") }
        assertThat(letters.kind).isEqualTo(ChangeKind.ADDITIVE)
        assertThat(letters.reasons).containsExactly("now depends on shared")
    }

    @Test
    fun `renaming a theme another catalog uses lists that catalog too, released after it`() {
        val tenant = tenantWith("impact-rename-user")
        withMediator {
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalogId(tenant, shared)), "Brand").execute()
            mediator.releaseNext(catalogId(tenant, shared))
            val invoice = TemplateId(TemplateKey.of("invoice"), catalogId(tenant, letters))
            CreateDocumentTemplate(invoice, "Invoice").execute().withRequiredDataExample()
            UpdateDocumentTemplate(invoice, themeId = ThemeKey.of("brand"), themeCatalog = catalogId(tenant, shared)).execute()
        }

        val plan = preview(tenant, address(tenant, CatalogResourceType.THEME, shared, "brand").movedTo(shared, "house"))

        // Letters gains and loses nothing, but its invoice is rewritten to name `house`: a fix,
        // which needs the release of shared that has `house`.
        val letters = plan.releases.single { it.catalog.endsWith("/letters") }
        assertThat(letters.kind).isEqualTo(ChangeKind.NONE)
        assertThat(letters.reasons).containsExactly("uses theme 'house' at its new address")
        assertThat(plan.releaseOrder.map { it.substringAfter('/') }).containsExactly("shared", "letters")
        // Both change content an author approved: said before applying, not first met as a refusal.
        assertThat(plan.warnings.filter { it.code == "ready-again" }.map { it.message.substringAfter("/") }).containsExactly(
            "letters, template 'invoice' changes and needs marking ready again before it is released",
            "shared, theme 'house' changes and needs marking ready again before it is released",
        )
    }

    /** `letters` with a theme `house` and a template `invoice` using it, released as 1.0.0 when [released]. */
    private fun lettersWithHouse(name: String, released: Boolean): TenantKey {
        val tenant = tenantWith(name)
        withMediator {
            CreateTheme(ThemeId(ThemeKey.of("house"), catalogId(tenant, letters)), "House").execute()
            val invoice = TemplateId(TemplateKey.of("invoice"), catalogId(tenant, letters))
            CreateDocumentTemplate(invoice, "Invoice").execute().withRequiredDataExample()
            UpdateDocumentTemplate(invoice, themeId = ThemeKey.of("house"), themeCatalog = catalogId(tenant, letters)).execute()
            if (released) mediator.releaseNext(catalogId(tenant, letters))
        }
        return tenant
    }
}

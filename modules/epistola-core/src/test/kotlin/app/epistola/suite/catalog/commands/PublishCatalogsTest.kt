// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.commands.CatalogPublishOutcome.Kind
import app.epistola.suite.catalog.queries.ListReleaseDependencies
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Publishing several catalogs at once: each after the ones of the selection it uses, each with the
 * drafts it builds on, and one refused leaving the others published.
 */
class PublishCatalogsTest : IntegrationTestBase() {

    private val shared = CatalogKey.of("shared")
    private val letters = CatalogKey.of("letters")
    private val forms = CatalogKey.of("forms")

    /** `shared` with a theme, `letters` with an invoice using it, `forms` on its own; nothing published. */
    private fun tenant(name: String): TenantKey = withMediator {
        val tenant = createTenant(name).id
        CreateCatalog(tenant, shared, "Shared").execute()
        CreateCatalog(tenant, letters, "Letters").execute()
        CreateCatalog(tenant, forms, "Forms").execute()
        CreateTheme(ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant), shared)), "Brand").execute()
        val invoice = TemplateId(TemplateKey.of("invoice"), testCatalog(TenantId(tenant), letters))
        CreateDocumentTemplate(invoice, "Invoice").execute().withRequiredDataExample()
        UpdateDocumentTemplate(invoice, themeId = ThemeKey.of("brand"), themeCatalog = testCatalog(invoice.tenantKey, shared)).execute()
        CreateTheme(ThemeId(ThemeKey.of("plain"), testCatalog(TenantId(tenant), forms)), "Plain").execute()
        tenant
    }

    private fun publish(tenant: TenantKey, catalogs: List<CatalogKey>, markReady: Boolean = true) = withMediator { PublishCatalogs(tenant, catalogs.map { testCatalog(tenant, it) }, markReady).execute() }

    @Test
    fun `a selection in any order is published dependencies first, and pinned to what was just published`() {
        val tenant = tenant("publish-several")

        val result = publish(tenant, listOf(letters, forms, shared))

        assertThat(result.outcomes.map { it.catalog.key to it.kind }).containsExactly(shared to Kind.PUBLISHED, letters to Kind.PUBLISHED, forms to Kind.PUBLISHED)
        assertThat(result.published.map { it.version }).containsOnly("1.0.0")
        assertThat(withMediator { ListReleaseDependencies(testCatalog(tenant, letters), "1.0.0").query() }.map { it.catalogKey to it.releaseVersion })
            .containsExactly(shared.value to "1.0.0")
    }

    @Test
    fun `a catalog a selected one builds on is published along, and one with nothing left is passed over`() {
        val tenant = tenant("publish-along")

        val first = publish(tenant, listOf(letters))
        assertThat(first.outcomes.single().alongWith).containsExactly("shared 1.0.0")

        val again = publish(tenant, listOf(letters, shared))
        assertThat(again.outcomes.map { it.catalog.key to it.kind }).containsExactly(shared to Kind.UNCHANGED, letters to Kind.UNCHANGED)
    }

    @Test
    fun `a refused catalog leaves the others published, and what uses it is not attempted`() {
        val tenant = tenant("publish-refused")

        // Not marked ready, nothing can be published; forms is marked ready by hand and goes through.
        withMediator { MarkCatalogReady(testCatalog(tenant, forms)).execute() }
        val result = publish(tenant, listOf(shared, letters, forms), markReady = false)

        val byCatalog = result.outcomes.associateBy { it.catalog.key }
        assertThat(byCatalog.getValue(shared).kind).isEqualTo(Kind.REFUSED)
        assertThat(byCatalog.getValue(shared).reason).contains("not ready")
        assertThat(byCatalog.getValue(letters).kind).isEqualTo(Kind.NOT_ATTEMPTED)
        assertThat(byCatalog.getValue(letters).reason).contains("shared")
        assertThat(byCatalog.getValue(forms).kind).isEqualTo(Kind.PUBLISHED)
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Choosing, in a picker, a resource of a catalog the catalog does not declare asks to add the
 * dependency first (`catalog-dependencies.js`): confirmed, the catalog is declared and the choice goes
 * through; declined, nothing changes and the picker shows what it showed before.
 */
class DeclareDependencyUiTest : BasePlaywrightTest() {

    private val letters = CatalogKey.of("letters")
    private val shared = CatalogKey.of("shared")

    private fun setUp(name: String): TenantKey {
        val tenant = TenantKey.of(createTenant(name).id.value)
        withMediator {
            CreateCatalog(tenant, letters, "Letters").execute()
            CreateCatalog(tenant, shared, "Shared").execute()
            CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), testCatalog(tenant, letters)), "Invoice", catalogVersion = null).execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), testCatalog(tenant, shared)), "Shared brand", catalogVersion = null).execute()
        }
        return tenant
    }

    private fun declared(tenant: TenantKey) = withMediator { ListCatalogDependencies(testCatalog(tenant, letters), catalogVersion = null).query() }.map { it.catalogKey }

    private fun theme(tenant: TenantKey) = withMediator { GetDocumentTemplate(TemplateId(TemplateKey.of("invoice"), testCatalog(tenant, letters)), catalogVersion = null).query()!!.themeKey }

    private fun openSettings(tenant: TenantKey) = gotoAndReady("/tenants/${tenant.value}/catalogs/${testCatalog(tenant, letters).address}/templates/invoice/settings")

    @Test
    fun `choosing another catalog's theme asks to add the dependency, and confirmed, declares it and sets the theme`() {
        val tenant = setUp("Declare Dependency Confirm")
        openSettings(tenant)

        page.locator("#theme-select").selectOption("${testCatalog(tenant, shared).address}/brand")
        assertThat(page.getByTestId("confirm-dialog-confirm")).hasText("Add dependency")
        page.getByTestId("confirm-dialog-confirm").click()
        page.htmxSettle()

        assertThat(page.locator("#theme-select")).hasValue("${testCatalog(tenant, shared).address}/brand")
        Assertions.assertThat(declared(tenant)).contains(shared.value)
        Assertions.assertThat(theme(tenant)).isEqualTo(ThemeKey.of("brand"))
    }

    @Test
    fun `declining leaves the catalog undeclared, the theme unset, and the picker as it was`() {
        val tenant = setUp("Declare Dependency Decline")
        openSettings(tenant)

        page.locator("#theme-select").selectOption("${testCatalog(tenant, shared).address}/brand")
        page.getByTestId("confirm-dialog-cancel").click()

        assertThat(page.locator("#theme-select")).hasValue("")
        Assertions.assertThat(declared(tenant)).doesNotContain(shared.value)
        Assertions.assertThat(theme(tenant)).isNull()
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.identity.ResolveCatalogResourceAddress
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.TEST_TENANT_ROLES_HEADER
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import org.junit.jupiter.api.Test
import java.util.regex.Pattern
import org.assertj.core.api.Assertions.assertThat as assertThatValue

/**
 * Reorganising catalogs in a real browser: the organise page and the single-resource dialog, their
 * script loading under the strict CSP, and what a move leaves on screen. The server's answers are
 * covered by the handler tests; this is what the reader actually sees.
 */
class CatalogOrganiseUiTest : BasePlaywrightTest() {

    private val letters = CatalogKey.of("letters")
    private val shared = CatalogKey.of("shared")

    private fun tenant(name: String): TenantKey {
        val tenant = createTenant(name).id
        withMediator {
            SaveFeatureToggle(tenant, KnownFeatures.RESOURCE_RELOCATION, enabled = true).execute()
            CreateCatalog(tenant, letters, "Letters").execute()
            CreateCatalog(tenant, shared, "Shared").execute()
        }
        return tenant
    }

    private fun stencil(tenant: TenantKey, catalog: CatalogKey, key: String, name: String) = withMediator {
        CreateStencil(StencilId(StencilKey.of(key), CatalogId(catalog, TenantId(tenant))), name).execute()
    }

    @Test
    fun `a selection moves to the shared destination and the page says where it went`() {
        val tenant = tenant("Organise in browser")
        stencil(tenant, letters, "header", "Header")

        gotoAndReady("/tenants/$tenant/catalogs/organise")
        page.getByLabel("Select Header").check()
        page.getByTestId("organise-shared-destination").selectOption("shared")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Preview")).click()
        assertThat(page.getByText("Ready to move")).isVisible()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Move 1 resource(s)")).click()

        assertThat(page.getByTestId("organise-applied")).containsText("Moved 1 resource to Shared")
        val resolved = withMediator { ResolveCatalogResourceAddress(tenant, ResourceAddress(CatalogResourceType.STENCIL, letters.value, "header")).query()!! }
        assertThatValue(resolved.canonical.catalogKey).isEqualTo(shared.value)
    }

    @Test
    fun `a blocked preview says why on the row it concerns, and offers no Move`() {
        val tenant = tenant("Organise blocked in browser")
        stencil(tenant, letters, "header", "Header")
        stencil(tenant, shared, "header", "Header in shared")

        gotoAndReady("/tenants/$tenant/catalogs/organise")
        page.getByLabel("Select Header", Page.GetByLabelOptions().setExact(true)).check()
        page.getByTestId("organise-shared-destination").selectOption("shared")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Preview")).click()

        assertThat(page.getByText("Blocked — nothing will be moved")).isVisible()
        assertThat(page.locator("tbody tr", Page.LocatorOptions().setHasText("Header")).first()).containsText("already a resource")
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(Pattern.compile("^Move ")))).hasCount(0)
    }

    @Test
    fun `moving a template from its settings lands on the settings at its new address`() {
        val tenant = tenant("Organise template dialog")
        withMediator { CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), CatalogId(letters, TenantId(tenant))), "Invoice").execute() }

        gotoAndReady("/tenants/$tenant/templates/letters/invoice/settings")
        val dialog = page.openDialogByTrigger(page.getByTestId("template-move-action"), "#move-resource-dialog")
        dialog.getByTestId("organise-single-destination").selectOption("shared")
        dialog.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Preview")).click()
        dialog.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Move 1 resource(s)")).click()

        assertThat(page).hasURL(Pattern.compile(".*/tenants/$tenant/templates/shared/invoice/settings$"))
    }

    @Test
    fun `a catalog viewer can preview a move and is told who can apply it`() {
        val tenant = tenant("Organise viewer in browser")
        stencil(tenant, letters, "header", "Header")
        page.setExtraHTTPHeaders(mapOf(TEST_TENANT_ROLES_HEADER to "CONTENT_VIEWER"))

        gotoAndReady("/tenants/$tenant/catalogs/organise")
        page.getByLabel("Select Header").check()
        page.getByTestId("organise-shared-destination").selectOption("shared")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Preview")).click()

        assertThat(page.getByTestId("organise-cannot-apply")).containsText("catalog management")
        assertThat(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(Pattern.compile("^Move ")))).hasCount(0)
    }
}

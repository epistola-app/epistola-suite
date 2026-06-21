package app.epistola.suite.ui

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.TestIdHelpers
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

/**
 * Browser coverage for the parts of the data-table that can only be proven in a real
 * page: the sort-header link click, and the page-size `<select>` whose change navigates
 * to a **server-built** `data-url` via the delegated listener (the path that replaced
 * the banned client-side URL construction). The server contract (params → query,
 * canonical HX-Push-Url, clamping, catalog filter) is asserted deterministically in
 * [app.epistola.suite.handlers.DocumentTemplateListHandlerHtmxTest]; the catalog filter
 * uses the identical data-url+listener path exercised by the page-size test here.
 */
class TemplateListSortPagePlaywrightTest : BasePlaywrightTest() {

    private fun seedTenantWithTemplates(count: Int): Tenant = withMediator {
        val tenant = CreateTenant(id = TenantKey.of("test-tpl-list-${System.nanoTime()}"), name = "Template List UI").execute()
        val tenantId = TenantId(tenant.id)
        (1..count).forEach { n ->
            CreateDocumentTemplate(
                id = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId)),
                name = "Template %02d".format(n),
            ).execute()
        }
        tenant
    }

    @Test
    fun `clicking the name header sorts the rows and flips direction, updating the URL`() {
        val tenant = seedTenantWithTemplates(3)
        gotoAndReady("/tenants/${tenant.id}/templates")

        // Sort ascending.
        page.locator("[data-sort-key='name']").click()
        page.htmxSettle()
        assertThat(page).hasURL(Pattern.compile(".*sort=name&dir=asc.*"))
        assertThat(page.getByTestId("template-row").first().locator("a").first()).hasText("Template 01")

        // Click the active column again → descending.
        page.locator("[data-sort-key='name']").click()
        page.htmxSettle()
        assertThat(page).hasURL(Pattern.compile(".*sort=name&dir=desc.*"))
        assertThat(page.getByTestId("template-row").first().locator("a").first()).hasText("Template 03")
    }

    @Test
    fun `the page-size select navigates via a server-built data-url and paginates`() {
        val tenant = seedTenantWithTemplates(26)
        gotoAndReady("/tenants/${tenant.id}/templates")

        // Default size 50 shows all 26 on one page. Shrink to 25 → two pages.
        page.getByTestId("page-size").selectOption("25")
        page.htmxSettle()
        assertThat(page).hasURL(Pattern.compile(".*size=25.*page=1.*"))
        assertThat(page.getByTestId("paging-summary")).containsText("of 26")

        // Advance to page 2 via the Next link.
        page.getByTestId("page-next").click()
        page.htmxSettle()
        assertThat(page).hasURL(Pattern.compile(".*page=2.*"))
        assertThat(page.getByTestId("paging-summary")).containsText("Showing 26")
    }
}

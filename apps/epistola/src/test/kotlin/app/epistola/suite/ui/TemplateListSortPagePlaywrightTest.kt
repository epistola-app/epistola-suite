package app.epistola.suite.ui

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.TestIdHelpers
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.SelectOption
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

/**
 * Browser coverage for the parts of the data-table that can only be proven in a real
 * page: the sort-header link click, and the live filter form — the search box, the
 * page-size `<select>` and the catalog `<select>` are all plain fields of one `<form>`
 * whose htmx request carries the whole state, so changing any one preserves the others
 * (the bug these tests lock: a filter change used to reset sort/size/the other filters).
 * The server contract (params → query, canonical HX-Push-Url, clamping) is asserted
 * deterministically in [app.epistola.suite.handlers.DocumentTemplateListHandlerHtmxTest].
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
    fun `clicking New Template navigates to the create page instead of nesting it in the table`() {
        val tenant = seedTenantWithTemplates(1)
        gotoAndReady("/tenants/${tenant.id}/templates")

        // The New Template action is a plain <a> nested inside the live-filter <form>.
        // With body-wide hx-boost it would otherwise inherit the form's
        // hx-target="#data-table-container" and swap the whole /new page into the table
        // (the "nested shell" bug) — it must navigate as a full page instead.
        page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("New Template")).click()
        page.htmxSettle()

        assertThat(page).hasURL(Pattern.compile(".*/templates/new$"))
        assertThat(page.getByTestId("create-form-submit")).isVisible()
        // The create form must NOT be nested inside the list's swap target.
        assertThat(page.locator("#data-table-container [data-testid='create-form-submit']")).hasCount(0)
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
    fun `the page-size select submits the filter form and paginates`() {
        val tenant = seedTenantWithTemplates(26)
        gotoAndReady("/tenants/${tenant.id}/templates")

        // Default size 10 paginates 26 across 3 pages; switch to 25 → two pages.
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

    @Test
    fun `searching preserves the chosen page size instead of reverting to the default`() {
        val tenant = seedTenantWithTemplates(30)
        gotoAndReady("/tenants/${tenant.id}/templates")

        // Pick a size that differs from the default (10), so a reset would be visible.
        page.getByTestId("page-size").selectOption("25")
        page.htmxSettle()
        assertThat(page).hasURL(Pattern.compile(".*size=25.*"))

        // The search box lives outside the swapped table; it must carry the LIVE
        // size=25, not revert to the page-load default of 10. (Retrying hasURL absorbs the
        // 300ms search debounce.) All 30 names contain "Template" → 25 of 30 shown.
        page.getByTestId("search-input").fill("Template")
        assertThat(page).hasURL(Pattern.compile(".*q=Template.*size=25.*"))
        assertThat(page.getByTestId("template-row")).hasCount(25)
    }

    @Test
    fun `filtering by catalog preserves the chosen page size`() {
        val tenant = withMediator {
            val t = CreateTenant(id = TenantKey.of("test-tpl-cat-${System.nanoTime()}"), name = "Catalog Filter UI").execute()
            val tenantId = TenantId(t.id)
            val catA = CatalogKey.of("cat-a")
            CreateCatalog(tenantKey = tenantId.key, id = catA, name = "Catalog A").execute()
            (1..30).forEach { n ->
                CreateDocumentTemplate(
                    id = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId(catA, tenantId)),
                    name = "Catalog Tpl %02d".format(n),
                ).execute()
            }
            t
        }
        gotoAndReady("/tenants/${tenant.id}/templates")

        // Pick a size that differs from the default (10).
        page.getByTestId("page-size").selectOption("25")
        page.htmxSettle()
        assertThat(page).hasURL(Pattern.compile(".*size=25.*"))

        // Selecting a catalog must keep the live size=25 rather than reverting to the
        // page-load default of 10. (cat-a has 30 → 25 shown on page 1.)
        page.getByTestId("catalog-filter").selectOption(SelectOption().setLabel("Catalog A"))
        assertThat(page).hasURL(Pattern.compile(".*catalog=cat-a.*size=25.*"))
        assertThat(page.getByTestId("template-row")).hasCount(25)
    }

    /**
     * Seeds two catalogs that share template names, so a search and a catalog filter
     * each change the visible set independently — the only way to prove the two compose
     * rather than one resetting the other. `cat-a` holds 5 "Alpha" + 5 "Beta"; the
     * default catalog holds 5 more "Alpha".
     */
    private fun seedTwoCatalogTenant(): Tenant = withMediator {
        val t = CreateTenant(id = TenantKey.of("test-tpl-xcat-${System.nanoTime()}"), name = "Cross Filter UI").execute()
        val tenantId = TenantId(t.id)
        val catA = CatalogKey.of("cat-a")
        CreateCatalog(tenantKey = tenantId.key, id = catA, name = "Catalog A").execute()
        (1..5).forEach { n ->
            CreateDocumentTemplate(id = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId(catA, tenantId)), name = "Alpha %02d".format(n)).execute()
            CreateDocumentTemplate(id = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId(catA, tenantId)), name = "Beta %02d".format(n)).execute()
            CreateDocumentTemplate(id = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId)), name = "Alpha %02d".format(n)).execute()
        }
        t
    }

    @Test
    fun `searching preserves an active catalog filter`() {
        val tenant = seedTwoCatalogTenant()
        gotoAndReady("/tenants/${tenant.id}/templates")

        page.getByTestId("catalog-filter").selectOption(SelectOption().setLabel("Catalog A"))
        page.htmxSettle()
        assertThat(page).hasURL(Pattern.compile(".*catalog=cat-a.*"))
        assertThat(page.getByTestId("template-row")).hasCount(10) // 5 Alpha + 5 Beta in cat-a

        // Searching must narrow WITHIN the active catalog, not silently reset it — so
        // "Alpha" matches the 5 in cat-a, never the 5 identically-named in the default.
        page.getByTestId("search-input").fill("Alpha")
        assertThat(page).hasURL(Pattern.compile(".*q=Alpha.*catalog=cat-a.*"))
        assertThat(page.getByTestId("template-row")).hasCount(5)
    }

    @Test
    fun `changing the catalog filter preserves an active search term`() {
        val tenant = seedTwoCatalogTenant()
        gotoAndReady("/tenants/${tenant.id}/templates")

        page.getByTestId("search-input").fill("Alpha")
        assertThat(page).hasURL(Pattern.compile(".*q=Alpha.*"))
        assertThat(page.getByTestId("template-row")).hasCount(10) // Alpha in cat-a (5) + default (5)

        // Switching catalog must keep the search term applied — Alpha in cat-a only.
        page.getByTestId("catalog-filter").selectOption(SelectOption().setLabel("Catalog A"))
        assertThat(page).hasURL(Pattern.compile(".*q=Alpha.*catalog=cat-a.*"))
        assertThat(page.getByTestId("template-row")).hasCount(5)
    }

    @Test
    fun `changing the page size keeps the active filter and renders only filtered rows`() {
        val tenant = withMediator {
            val t = CreateTenant(id = TenantKey.of("test-tpl-filtersize-${System.nanoTime()}"), name = "Filter+Size UI").execute()
            val tenantId = TenantId(t.id)
            val catA = CatalogKey.of("cat-a")
            CreateCatalog(tenantKey = tenantId.key, id = catA, name = "Catalog A").execute()
            (1..12).forEach { n ->
                CreateDocumentTemplate(id = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId(catA, tenantId)), name = "In A %02d".format(n)).execute()
            }
            (1..8).forEach { n ->
                CreateDocumentTemplate(id = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId)), name = "In Default %02d".format(n)).execute()
            }
            t
        }
        gotoAndReady("/tenants/${tenant.id}/templates")

        // Apply the catalog filter — 12 of the 20 templates.
        page.getByTestId("catalog-filter").selectOption(SelectOption().setLabel("Catalog A"))
        page.htmxSettle()
        assertThat(page).hasURL(Pattern.compile(".*catalog=cat-a.*"))

        // Changing the page size must KEEP the filter AND keep what is rendered actually
        // filtered: size 25 > 12, so exactly the 12 cat-a templates render — never the 20
        // total — and no default-catalog row leaks in.
        page.getByTestId("page-size").selectOption("25")
        assertThat(page).hasURL(Pattern.compile(".*catalog=cat-a.*size=25.*"))
        assertThat(page.getByTestId("template-row")).hasCount(12)
        assertThat(page.getByText("In Default 01")).hasCount(0)
    }
}

package app.epistola.suite.templates.queries

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.paging.PageRequest
import app.epistola.suite.common.paging.SortDirection
import app.epistola.suite.common.paging.SortSpec
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ListTemplateSummariesTest : IntegrationTestBase() {

    private fun createTemplates(tenantId: TenantId, vararg names: String) {
        names.forEach { name ->
            CreateDocumentTemplate(
                id = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId)),
                name = name,
            ).execute()
        }
    }

    @Test
    fun `returns a windowed page while total counts every match`(): Unit = withMediator {
        val tenantId = TenantId(createTenant("Test Tenant").id)
        createTemplates(tenantId, "Alpha", "Bravo", "Charlie")

        val result = ListTemplateSummaries(
            tenantId = tenantId,
            page = PageRequest(page = 1, size = 2),
        ).query()

        assertThat(result.items).hasSize(2)
        assertThat(result.total).isEqualTo(3L)
        assertThat(result.page).isEqualTo(1)
        assertThat(result.size).isEqualTo(2)
        assertThat(result.totalPages).isEqualTo(2)
    }

    @Test
    fun `sorts by a whitelisted column in the requested direction`(): Unit = withMediator {
        val tenantId = TenantId(createTenant("Test Tenant").id)
        createTemplates(tenantId, "Charlie", "Alpha", "Bravo")

        val result = ListTemplateSummaries(
            tenantId = tenantId,
            sort = SortSpec("name", SortDirection.ASC),
            page = PageRequest(page = 1, size = 50),
        ).query()

        assertThat(result.items.map { it.name }).containsExactly("Alpha", "Bravo", "Charlie")
    }

    @Test
    fun `clamps an out-of-range page to the last page instead of returning empty`(): Unit = withMediator {
        val tenantId = TenantId(createTenant("Test Tenant").id)
        createTemplates(tenantId, "Alpha", "Bravo", "Charlie")

        // 3 templates at size 2 => 2 pages. Request page 5 (e.g. a stale bookmark).
        val result = ListTemplateSummaries(
            tenantId = tenantId,
            page = PageRequest(page = 5, size = 2),
        ).query()

        assertThat(result.page).isEqualTo(2)
        assertThat(result.total).isEqualTo(3L)
        assertThat(result.totalPages).isEqualTo(2)
        assertThat(result.items).hasSize(1)
    }

    @Test
    fun `falls back to the default sort when the sort column is not whitelisted`(): Unit = withMediator {
        val tenantId = TenantId(createTenant("Test Tenant").id)
        createTemplates(tenantId, "Alpha", "Bravo", "Charlie")

        // An injection-shaped column must never reach SQL: it falls back to the default
        // sort, so the query runs safely and returns every row.
        val result = ListTemplateSummaries(
            tenantId = tenantId,
            sort = SortSpec("dt.name); DROP TABLE document_templates;--", SortDirection.ASC),
            page = PageRequest(page = 1, size = 50),
        ).query()

        assertThat(result.items).hasSize(3)
        assertThat(result.total).isEqualTo(3L)
    }

    @Test
    fun `empty tenant yields an empty first page with zero total`(): Unit = withMediator {
        val tenantId = TenantId(createTenant("Empty Tenant").id)

        val result = ListTemplateSummaries(tenantId = tenantId, page = PageRequest(page = 1, size = 50)).query()

        assertThat(result.items).isEmpty()
        assertThat(result.total).isEqualTo(0L)
        assertThat(result.page).isEqualTo(1)
        assertThat(result.totalPages).isEqualTo(1)
    }

    @Test
    fun `sorts by a whitelisted column descending`(): Unit = withMediator {
        val tenantId = TenantId(createTenant("Test Tenant").id)
        createTemplates(tenantId, "Alpha", "Bravo", "Charlie")

        val result = ListTemplateSummaries(
            tenantId = tenantId,
            sort = SortSpec("name", SortDirection.DESC),
            page = PageRequest(page = 1, size = 50),
        ).query()

        assertThat(result.items.map { it.name }).containsExactly("Charlie", "Bravo", "Alpha")
    }

    @Test
    fun `an in-range later page returns its window with the full total`(): Unit = withMediator {
        val tenantId = TenantId(createTenant("Test Tenant").id)
        createTemplates(tenantId, "Alpha", "Bravo", "Charlie")

        val result = ListTemplateSummaries(
            tenantId = tenantId,
            sort = SortSpec("name", SortDirection.ASC),
            page = PageRequest(page = 2, size = 2),
        ).query()

        assertThat(result.page).isEqualTo(2)
        assertThat(result.total).isEqualTo(3L)
        assertThat(result.items.map { it.name }).containsExactly("Charlie")
    }

    @Test
    fun `total reflects the filtered set, not the whole table`(): Unit = withMediator {
        val tenantId = TenantId(createTenant("Test Tenant").id)
        createTemplates(tenantId, "Invoice A", "Invoice B", "Report")

        val result = ListTemplateSummaries(
            tenantId = tenantId,
            searchTerm = "Invoice",
            page = PageRequest(page = 1, size = 1),
        ).query()

        assertThat(result.total).isEqualTo(2L)
        assertThat(result.totalPages).isEqualTo(2)
        assertThat(result.items).hasSize(1)
    }

    @Test
    fun `paging stays stable when the sort column ties`(): Unit = withMediator {
        val tenantId = TenantId(createTenant("Test Tenant").id)
        // Fresh templates all have 0 published versions, so sorting by "published"
        // ties on every row — the dt.id tiebreaker must keep paging deterministic.
        createTemplates(tenantId, "Alpha", "Bravo", "Charlie")
        val sort = SortSpec("published", SortDirection.ASC)

        val seen = (1..3).map { p ->
            ListTemplateSummaries(tenantId = tenantId, sort = sort, page = PageRequest(page = p, size = 1)).query().items.single()
        }

        assertThat(seen.map { it.id }).doesNotHaveDuplicates()
        assertThat(seen.map { it.name }).containsExactlyInAnyOrder("Alpha", "Bravo", "Charlie")
    }

    @Test
    fun `paging stays deterministic across catalogs that share a template id`(): Unit = withMediator {
        val tenantId = TenantId(createTenant("Cross Catalog").id)
        // Two catalogs each hold a template with the SAME id and name (the product's
        // catalog-override model). Sorting by name ties on name AND on the bare id, so paging is
        // deterministic only because catalog_key is part of the tiebreaker — without it a row
        // could be skipped or duplicated across the page boundary.
        val catA = CatalogKey.of("cat-a")
        val catB = CatalogKey.of("cat-b")
        CreateCatalog(tenantKey = tenantId.key, id = catA, name = "Catalog A").execute()
        CreateCatalog(tenantKey = tenantId.key, id = catB, name = "Catalog B").execute()
        CreateDocumentTemplate(id = TemplateId(TemplateKey.of("shared"), CatalogId(catA, tenantId)), name = "Shared").execute()
        CreateDocumentTemplate(id = TemplateId(TemplateKey.of("shared"), CatalogId(catB, tenantId)), name = "Shared").execute()

        val sort = SortSpec("name", SortDirection.ASC)
        val seen = (1..2).map { p ->
            ListTemplateSummaries(tenantId = tenantId, sort = sort, page = PageRequest(page = p, size = 1)).query().items.single()
        }

        // Each same-id row appears exactly once across the two pages — none skipped or duplicated.
        assertThat(seen.map { it.catalogKey.value }).containsExactlyInAnyOrder("cat-a", "cat-b")
    }

    @Test
    fun `a huge page number clamps to the last page without overflowing the offset`(): Unit = withMediator {
        val tenantId = TenantId(createTenant("Huge Page").id)
        createTemplates(tenantId, "Alpha", "Bravo", "Charlie")

        // page = Int.MAX_VALUE: an Int offset (page-1)*size would overflow to a negative SQL
        // OFFSET (a 500); computed in Long it stays positive, the fetch is empty, and the
        // stale-deep-link clamp returns the last page instead of throwing.
        val result = ListTemplateSummaries(
            tenantId = tenantId,
            page = PageRequest(page = Int.MAX_VALUE, size = 2),
        ).query()

        assertThat(result.page).isEqualTo(2)
        assertThat(result.total).isEqualTo(3L)
        assertThat(result.items).hasSize(1)
    }
}

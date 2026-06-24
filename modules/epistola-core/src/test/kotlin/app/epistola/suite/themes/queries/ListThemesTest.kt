package app.epistola.suite.themes.queries

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.paging.PageRequest
import app.epistola.suite.common.paging.SortDirection
import app.epistola.suite.common.paging.SortSpec
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `themes` is the reference page proving the shared paging/sorting toolkit end-to-end (ADR 0007).
 * Beyond the basic windowed-page/sort/search behaviour, this carries the foundation's correctness
 * regressions against a real query: the huge-page offset clamp and the cross-catalog tiebreaker.
 */
class ListThemesTest : IntegrationTestBase() {

    /** Themes scoped to the tenant's own (default) catalog, so seeded `system` themes don't skew counts. */
    private fun theme(tenantId: TenantId, catalog: CatalogKey, slug: String, name: String) = CreateTheme(id = ThemeId(ThemeKey.of(slug), CatalogId(catalog, tenantId)), name = name).execute()

    private fun listDefault(tenantId: TenantId, sort: SortSpec, page: PageRequest, search: String? = null) = ListThemes(tenantId = tenantId, searchTerm = search, catalogKey = CatalogKey.DEFAULT, sort = sort, page = page).query()

    private val byName = SortSpec("name", SortDirection.ASC)

    @Test
    fun `returns a windowed page while total counts every match`(): Unit = withMediator {
        val tenantId = TenantId(createTenant("Themes Page").id)
        listOf("alpha" to "Alpha", "bravo" to "Bravo", "charlie" to "Charlie").forEach { (s, n) -> theme(tenantId, CatalogKey.DEFAULT, s, n) }

        val result = listDefault(tenantId, byName, PageRequest(page = 1, size = 2))

        assertThat(result.items).hasSize(2)
        assertThat(result.total).isEqualTo(3L)
        assertThat(result.totalPages).isEqualTo(2)
    }

    @Test
    fun `sorts by a whitelisted column in the requested direction`(): Unit = withMediator {
        val tenantId = TenantId(createTenant("Themes Sort").id)
        listOf("charlie" to "Charlie", "alpha" to "Alpha", "bravo" to "Bravo").forEach { (s, n) -> theme(tenantId, CatalogKey.DEFAULT, s, n) }

        val asc = listDefault(tenantId, byName, PageRequest(page = 1, size = 50)).items.map { it.name }
        assertThat(asc).containsExactly("Alpha", "Bravo", "Charlie")
    }

    @Test
    fun `total reflects the filtered set, not the whole catalog`(): Unit = withMediator {
        val tenantId = TenantId(createTenant("Themes Search").id)
        listOf("invoice-a" to "Invoice A", "invoice-b" to "Invoice B", "report" to "Report").forEach { (s, n) -> theme(tenantId, CatalogKey.DEFAULT, s, n) }

        val result = listDefault(tenantId, byName, PageRequest(page = 1, size = 1), search = "Invoice")

        assertThat(result.total).isEqualTo(2L)
        assertThat(result.items).hasSize(1)
    }

    @Test
    fun `falls back to the default sort when the sort column is not whitelisted`(): Unit = withMediator {
        val tenantId = TenantId(createTenant("Themes Injection").id)
        listOf("alpha" to "Alpha", "bravo" to "Bravo").forEach { (s, n) -> theme(tenantId, CatalogKey.DEFAULT, s, n) }

        // An injection-shaped column must never reach SQL — it falls back to the default sort.
        val result = listDefault(tenantId, SortSpec("t.name); DROP TABLE themes;--", SortDirection.ASC), PageRequest(page = 1, size = 50))

        assertThat(result.items).hasSize(2)
    }

    @Test
    fun `clamps an out-of-range page to the last page instead of returning empty`(): Unit = withMediator {
        val tenantId = TenantId(createTenant("Themes Clamp").id)
        listOf("alpha" to "Alpha", "bravo" to "Bravo", "charlie" to "Charlie").forEach { (s, n) -> theme(tenantId, CatalogKey.DEFAULT, s, n) }

        // 3 themes at size 2 => 2 pages. A stale deep-link to page 5 clamps to the last page.
        val result = listDefault(tenantId, byName, PageRequest(page = 5, size = 2))

        assertThat(result.page).isEqualTo(2)
        assertThat(result.total).isEqualTo(3L)
        assertThat(result.items).hasSize(1)
    }

    @Test
    fun `a huge page number clamps to the last page without overflowing the offset`(): Unit = withMediator {
        val tenantId = TenantId(createTenant("Themes Huge Page").id)
        listOf("alpha" to "Alpha", "bravo" to "Bravo", "charlie" to "Charlie").forEach { (s, n) -> theme(tenantId, CatalogKey.DEFAULT, s, n) }

        // page = Int.MAX_VALUE: an Int offset (page-1)*size would overflow to a negative SQL OFFSET
        // (a 500); computed in Long it stays positive and the clamp returns the last page.
        val result = listDefault(tenantId, byName, PageRequest(page = Int.MAX_VALUE, size = 2))

        assertThat(result.page).isEqualTo(2)
        assertThat(result.total).isEqualTo(3L)
        assertThat(result.items).hasSize(1)
    }

    @Test
    fun `paging stays deterministic across catalogs that share a theme id`(): Unit = withMediator {
        val tenantId = TenantId(createTenant("Themes Cross Catalog").id)
        // Two catalogs each hold a theme with the SAME id and name (the catalog-override case). The
        // bare id ties, so paging is only deterministic because catalog_key is part of the tiebreaker.
        val catA = CatalogKey.of("cat-a")
        val catB = CatalogKey.of("cat-b")
        CreateCatalog(tenantKey = tenantId.key, id = catA, name = "Catalog A").execute()
        CreateCatalog(tenantKey = tenantId.key, id = catB, name = "Catalog B").execute()
        theme(tenantId, catA, "shared", "Shared")
        theme(tenantId, catB, "shared", "Shared")

        // Walk every page (size 1) across all catalogs; no row may be skipped or duplicated.
        val sort = SortSpec("name", SortDirection.ASC)
        val total = ListThemes(tenantId = tenantId, sort = sort, page = PageRequest(page = 1, size = 1)).query().total
        val seen = (1..total.toInt()).map { p ->
            ListThemes(tenantId = tenantId, sort = sort, page = PageRequest(page = p, size = 1)).query().items.single()
        }

        assertThat(seen.map { "${it.catalogKey.value}/${it.id.value}" }).doesNotHaveDuplicates()
        assertThat(seen.filter { it.id.value == "shared" }.map { it.catalogKey.value }).containsExactlyInAnyOrder("cat-a", "cat-b")
    }
}

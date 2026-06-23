package app.epistola.suite.handlers

import app.epistola.suite.common.paging.SortDirection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The catalog list sorts but deliberately does NOT paginate, so its sort-link authority
 * builds clean `?q=&sort=&dir=` URLs with no `size`/`page` (unlike the paginated data-table's
 * `ListQuery`). These are pure URL-contract assertions — the load-bearing piece, since the
 * pushed canonical URL is what makes refresh and bookmarking work.
 */
class CatalogListSortTest {

    private fun sort(
        sortKey: String = "name",
        direction: SortDirection = SortDirection.ASC,
        search: String? = null,
    ) = CatalogListSort(basePath = "/tenants/acme/catalogs", search = search, sortKey = sortKey, direction = direction)

    @Test
    fun `clicking a different column sorts it ascending`() {
        assertThat(sort(sortKey = "name").sortUrl("id"))
            .isEqualTo("/tenants/acme/catalogs?sort=id&dir=asc")
    }

    @Test
    fun `clicking the active ascending column flips to descending`() {
        assertThat(sort(sortKey = "name", direction = SortDirection.ASC).sortUrl("name"))
            .isEqualTo("/tenants/acme/catalogs?sort=name&dir=desc")
    }

    @Test
    fun `clicking the active descending column flips back to ascending`() {
        assertThat(sort(sortKey = "name", direction = SortDirection.DESC).sortUrl("name"))
            .isEqualTo("/tenants/acme/catalogs?sort=name&dir=asc")
    }

    @Test
    fun `the active search term rides every sort link and the canonical url`() {
        val sorted = sort(search = "inv", sortKey = "type", direction = SortDirection.DESC)
        assertThat(sorted.sortUrl("name")).isEqualTo("/tenants/acme/catalogs?q=inv&sort=name&dir=asc")
        assertThat(sorted.canonicalUrl()).isEqualTo("/tenants/acme/catalogs?q=inv&sort=type&dir=desc")
    }

    @Test
    fun `urls carry no size or page - catalogs do not paginate`() {
        val url = sort(search = "x").sortUrl("updated")
        assertThat(url).doesNotContain("size=").doesNotContain("page=")
    }

    @Test
    fun `isSorted and ascending reflect the current state`() {
        val sorted = sort(sortKey = "type", direction = SortDirection.DESC)
        assertThat(sorted.isSorted("type")).isTrue()
        assertThat(sorted.isSorted("name")).isFalse()
        assertThat(sorted.ascending()).isFalse()
    }
}

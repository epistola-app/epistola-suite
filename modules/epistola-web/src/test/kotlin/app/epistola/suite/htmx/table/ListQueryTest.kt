package app.epistola.suite.htmx.table

import app.epistola.suite.common.paging.SortDirection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the server-side URL contract. Every list-control URL is built here in Kotlin,
 * never in client JS — so these exact-string assertions are the load-bearing proof
 * that state round-trips through the query string deterministically.
 */
class ListQueryTest {

    private val base = ListQuery(
        basePath = "/tenants/acme/templates",
        q = null,
        catalog = null,
        sortKey = "updated",
        direction = SortDirection.DESC,
        page = 1,
        size = 50,
    )

    @Test
    fun `canonical url reflects the current state`() {
        assertThat(base.canonicalUrl())
            .isEqualTo("/tenants/acme/templates?sort=updated&dir=desc&size=50&page=1")
    }

    @Test
    fun `sort url on a new column is ascending and resets page`() {
        assertThat(base.copy(page = 3).sortUrl("name"))
            .isEqualTo("/tenants/acme/templates?sort=name&dir=asc&size=50&page=1")
    }

    @Test
    fun `sort url flips to descending on the active ascending column`() {
        val q = base.copy(sortKey = "name", direction = SortDirection.ASC)
        assertThat(q.sortUrl("name"))
            .isEqualTo("/tenants/acme/templates?sort=name&dir=desc&size=50&page=1")
    }

    @Test
    fun `page url preserves sort and size and changes only page`() {
        val q = base.copy(sortKey = "name", direction = SortDirection.ASC, size = 25)
        assertThat(q.pageUrl(4))
            .isEqualTo("/tenants/acme/templates?sort=name&dir=asc&size=25&page=4")
    }

    @Test
    fun `null and blank params are omitted`() {
        assertThat(base.canonicalUrl()).doesNotContain("catalog").doesNotContain("q=")
    }

    @Test
    fun `query term is url-encoded so it cannot break out of the param`() {
        assertThat(base.copy(q = "a&b").canonicalUrl()).contains("q=a%26b")
    }

    @Test
    fun `isSorted and ascending report the active sort state`() {
        val q = base.copy(sortKey = "name", direction = SortDirection.ASC)
        assertThat(q.isSorted("name")).isTrue()
        assertThat(q.isSorted("updated")).isFalse()
        assertThat(q.ascending()).isTrue()
    }
}

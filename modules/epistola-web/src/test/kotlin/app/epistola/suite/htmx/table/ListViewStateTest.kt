package app.epistola.suite.htmx.table

import app.epistola.suite.common.paging.SortDirection
import app.epistola.suite.common.paging.SortSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.function.ServerRequest

/**
 * `ListViewState.from` is the untrusted-input clamping authority every data-table list page
 * runs at the UI boundary (ADR 0007): an unknown sort key falls back to the default, an
 * off-list page size falls back to the smallest, the page floors at 1, and only non-blank
 * named filters survive (in declaration order). These are the security-relevant guarantees,
 * so they get a direct unit test rather than only being exercised through a handler.
 */
class ListViewStateTest {

    private val sortable = setOf("name", "updated")
    private val defaultSort = SortSpec("updated", SortDirection.DESC)
    private val pageSizes = listOf(10, 25, 50)
    private val filterNames = listOf("q", "catalog")

    private fun state(vararg params: Pair<String, String>): ListViewState {
        val mock = MockHttpServletRequest()
        params.forEach { (name, value) -> mock.setParameter(name, value) }
        val request = ServerRequest.create(mock, emptyList())
        return ListViewState.from(request, "/base", sortable, defaultSort, pageSizes, filterNames)
    }

    @Test
    fun `an unknown sort key falls back to the default column and direction`() {
        // "drop table" is not in the whitelist, so it must never reach the query.
        val s = state("sort" to "drop table")
        assertThat(s.sort).isEqualTo(SortSpec("updated", SortDirection.DESC))
    }

    @Test
    fun `a whitelisted sort key with an explicit direction is honoured`() {
        val s = state("sort" to "name", "dir" to "asc")
        assertThat(s.sort).isEqualTo(SortSpec("name", SortDirection.ASC))
    }

    @Test
    fun `dir is case-insensitive and a garbage dir falls back to the default direction`() {
        assertThat(state("sort" to "name", "dir" to "DESC").sort.direction).isEqualTo(SortDirection.DESC)
        // Garbage dir on a whitelisted column → the default sort's direction (DESC here).
        assertThat(state("sort" to "name", "dir" to "sideways").sort.direction).isEqualTo(SortDirection.DESC)
    }

    @Test
    fun `an off-list page size falls back to the smallest offered size`() {
        assertThat(state("size" to "7").pageRequest.size).isEqualTo(10)
        assertThat(state("size" to "1000").pageRequest.size).isEqualTo(10)
        assertThat(state("size" to "not-a-number").pageRequest.size).isEqualTo(10)
    }

    @Test
    fun `an offered page size is honoured`() {
        assertThat(state("size" to "50").pageRequest.size).isEqualTo(50)
    }

    @Test
    fun `page is floored at 1 for zero, negatives and junk`() {
        assertThat(state("page" to "0").pageRequest.page).isEqualTo(1)
        assertThat(state("page" to "-5").pageRequest.page).isEqualTo(1)
        assertThat(state("page" to "abc").pageRequest.page).isEqualTo(1)
        assertThat(state("page" to "3").pageRequest.page).isEqualTo(3)
    }

    @Test
    fun `a very large page is accepted (floored only), for the backing query to clamp`() {
        // There is no upper clamp here: a huge page passes through (the query clamps a stale,
        // out-of-range page to the last page). The offset is computed in Long downstream, so
        // (page - 1) * size can't overflow to a negative SQL OFFSET. See ADR 0007.
        assertThat(state("page" to "2000000000").pageRequest.page).isEqualTo(2000000000)
    }

    @Test
    fun `defaults apply when nothing is supplied`() {
        val s = state()
        assertThat(s.sort).isEqualTo(SortSpec("updated", SortDirection.DESC))
        assertThat(s.pageRequest.size).isEqualTo(10)
        assertThat(s.pageRequest.page).isEqualTo(1)
        assertThat(s.filter("q")).isNull()
    }

    @Test
    fun `only non-blank named filters are captured, in declaration order`() {
        val s = state("catalog" to "demo", "q" to "invoice", "ignored" to "x")
        assertThat(s.filter("q")).isEqualTo("invoice")
        assertThat(s.filter("catalog")).isEqualTo("demo")
        // An un-declared param is never captured as a filter.
        assertThat(s.filter("ignored")).isNull()
        // Declaration order (q before catalog) is preserved on the round-trip query.
        assertThat(s.toQuery(1).filters.keys.toList()).containsExactly("q", "catalog")
    }

    @Test
    fun `a blank filter value is dropped, not carried as an empty filter`() {
        val s = state("q" to "", "catalog" to "demo")
        assertThat(s.filter("q")).isNull()
        assertThat(s.toQuery(1).filters).containsOnlyKeys("catalog")
    }

    @Test
    fun `toQuery reflects the effective page the backing query clamped to`() {
        // A stale deep-link to page 99 is clamped by the query; the pushed URL must reflect
        // the page actually rendered, not the page asked for.
        val s = state("page" to "99")
        assertThat(s.toQuery(2).page).isEqualTo(2)
    }
}

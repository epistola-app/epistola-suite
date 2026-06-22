package app.epistola.suite.htmx.table

import app.epistola.suite.common.paging.SortDirection
import org.springframework.web.util.UriComponentsBuilder

/**
 * A sortable column descriptor for the shared `data-table` fragment. [label] is the
 * header text; [sortKey] is the logical query key the backing query whitelists
 * ("name", "variants", "updated"). A null [sortKey] means the column is not sortable.
 *
 * [width] is an optional CSS width for the column's `<col>` (e.g. "10rem", "20%"), used
 * with the table's fixed layout to keep widths predictable regardless of content. Null
 * lets the column flex to share the remaining space (cells truncate with an ellipsis).
 */
data class Column(val label: String, val sortKey: String? = null, val width: String? = null)

/**
 * Immutable, parsed-and-clamped state of a list view plus its base path, and the
 * authority for building the **navigation** URLs — sort headers and pagination —
 * server-side. Filter state (search term, catalog, page size) travels via the enclosing
 * list `<form>` instead (htmx serializes its fields), so this builds only the `<a hx-get>`
 * link URLs, which the data-table fragment renders fresh on every swap. See ADR 0007.
 *
 * State lives entirely in the query string, so the same [basePath] serves both full-page
 * and HTMX requests, and the view is bookmarkable.
 */
data class ListQuery(
    val basePath: String,
    val q: String?,
    val catalog: String?,
    val sortKey: String,
    val direction: SortDirection,
    val page: Int,
    val size: Int,
) {
    /** Canonical URL for the current state — the handler passes this to `pushUrl(...)`. */
    fun canonicalUrl(): String = url()

    /**
     * Sort by [columnKey]: ascending normally; if it is already the active sort and
     * ascending, flip to descending. Always resets to page 1.
     */
    fun sortUrl(columnKey: String): String {
        val nextDirection =
            if (columnKey == sortKey && direction == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
        return url(sort = columnKey, dir = nextDirection, page = 1)
    }

    /** Go to [targetPage], preserving every other dimension. */
    fun pageUrl(targetPage: Int): String = url(page = targetPage)

    fun isSorted(columnKey: String): Boolean = columnKey == sortKey

    fun ascending(): Boolean = direction == SortDirection.ASC

    private fun url(
        q: String? = this.q,
        catalog: String? = this.catalog,
        sort: String = this.sortKey,
        dir: SortDirection = this.direction,
        size: Int = this.size,
        page: Int = this.page,
    ): String {
        val builder = UriComponentsBuilder.fromPath(basePath)
        if (!q.isNullOrBlank()) builder.queryParam("q", q)
        if (!catalog.isNullOrBlank()) builder.queryParam("catalog", catalog)
        builder.queryParam("sort", sort)
        builder.queryParam("dir", if (dir == SortDirection.ASC) "asc" else "desc")
        builder.queryParam("size", size)
        builder.queryParam("page", page)
        // UriComponentsBuilder percent-encodes values — the server-side replacement for
        // the banned client-side encodeURIComponent.
        return builder.build().encode().toUriString()
    }
}

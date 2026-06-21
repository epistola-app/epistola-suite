package app.epistola.suite.htmx.table

import app.epistola.suite.common.paging.SortDirection
import org.springframework.web.util.UriComponentsBuilder

/**
 * A sortable column descriptor for the shared `data-table` fragment. [label] is the
 * header text; [sortKey] is the logical query key the backing query whitelists
 * ("name", "variants", "updated"). A null [sortKey] means the column is not sortable.
 */
data class Column(val label: String, val sortKey: String? = null)

/**
 * Immutable, parsed-and-clamped state of a list view plus its base path, and the
 * single authority for building every sort/page/size/search URL — **server-side**.
 *
 * The data-table fragment calls these methods at render time, so no URL is ever
 * assembled in client JavaScript. State lives entirely in the query string, so the
 * same [basePath] serves both full-page and HTMX requests. See ADR 0007.
 *
 * The recognized param vocabulary is the fixed six below. If a future list page needs
 * an extra filter param, generalize [url] with an `extraParams` map — out of scope today.
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

    /** Change page [targetSize], resetting to page 1. */
    fun sizeUrl(targetSize: Int): String = url(size = targetSize, page = 1)

    /**
     * Base URL for the search box: carries sort/dir/size/catalog and resets to page 1,
     * but **drops `q`** so HTMX appends the live `name="q"` input value onto a URL that
     * already preserves the rest of the state.
     */
    fun searchUrl(): String = url(q = null, page = 1)

    /**
     * Override one or more params by `key, value` pairs (e.g. `with("catalog", id, "page", "1")`),
     * preserving the rest. A blank value clears that param. Used by page-specific filter
     * controls (the catalog dropdown). Recognized keys: q, catalog, sort, dir, size, page.
     */
    fun with(vararg pairs: String): String {
        require(pairs.size % 2 == 0) { "with() needs an even number of key/value arguments" }
        val overrides = HashMap<String, String>()
        var i = 0
        while (i < pairs.size) {
            overrides[pairs[i]] = pairs[i + 1]
            i += 2
        }
        return url(
            q = if (overrides.containsKey("q")) overrides["q"] else q,
            catalog = if (overrides.containsKey("catalog")) overrides["catalog"] else catalog,
            sort = overrides["sort"] ?: sortKey,
            dir = overrides["dir"]?.toDirection() ?: direction,
            size = overrides["size"]?.toInt() ?: size,
            page = overrides["page"]?.toInt() ?: page,
        )
    }

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

    private fun String.toDirection(): SortDirection = if (equals("asc", ignoreCase = true)) SortDirection.ASC else SortDirection.DESC
}

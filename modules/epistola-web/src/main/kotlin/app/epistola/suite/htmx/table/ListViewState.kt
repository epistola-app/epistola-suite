package app.epistola.suite.htmx.table

import app.epistola.suite.common.paging.PageRequest
import app.epistola.suite.common.paging.SortDirection
import app.epistola.suite.common.paging.SortSpec
import app.epistola.suite.htmx.queryParam
import app.epistola.suite.htmx.queryParamInt
import org.springframework.web.servlet.function.ServerRequest

/**
 * The parsed-and-clamped state of a data-table list request, read once from the query
 * string at the UI boundary. Centralises the untrusted-input clamping ADR 0007 requires
 * (unknown sort key → default, off-list page size → default, page floored at 1) so every
 * list page does it identically instead of re-deriving it in each handler.
 *
 * Filter params are opaque here: a page names them in [from]'s `filterNames`, and they are
 * carried through to both the backing query (via [filter]) and the navigation links (via
 * [toQuery]) without this type knowing what any of them mean.
 *
 * Usage in a handler:
 * ```
 * val state = ListViewState.from(request, basePath, SORTABLE, DEFAULT_SORT, PAGE_SIZES, listOf("q", "catalog"))
 * val paged = ListThemes(tenantId, state.filter("q"), state.filter("catalog"), state.sort, state.pageRequest).query()
 * val query = state.toQuery(paged.page)   // effective page after the query clamps a stale deep-link
 * ```
 */
class ListViewState private constructor(
    val basePath: String,
    val sortKey: String,
    val direction: SortDirection,
    val size: Int,
    val page: Int,
    val filters: Map<String, String>,
) {
    val sort: SortSpec get() = SortSpec(sortKey, direction)
    val pageRequest: PageRequest get() = PageRequest(page, size)

    /** The parsed value of a named filter (e.g. "q", "catalog"); null if absent or blank. */
    fun filter(name: String): String? = filters[name]

    /**
     * Build the link/URL authority for the data-table once the backing query has clamped
     * to its [effectivePage] (a stale out-of-range page is clamped in the query, so the
     * pushed/canonical URL must reflect what was actually rendered, not what was asked).
     */
    fun toQuery(effectivePage: Int): ListQuery = ListQuery(
        basePath = basePath,
        filters = filters,
        sortKey = sortKey,
        direction = direction,
        page = effectivePage,
        size = size,
    )

    companion object {
        /**
         * Parse and clamp the list state from [request]'s query string.
         *
         * @param sortable    the logical sort keys this page allows (anything else → [defaultSort])
         * @param defaultSort applied when `sort` is absent/unknown; its direction is also the
         *                    default for `dir`
         * @param pageSizes   the offered page sizes; `size` outside this set → the first (smallest)
         * @param filterNames the page-specific filter params to read off the query string, in the
         *                    order they should appear in the canonical URL
         */
        fun from(
            request: ServerRequest,
            basePath: String,
            sortable: Set<String>,
            defaultSort: SortSpec,
            pageSizes: List<Int>,
            filterNames: List<String> = emptyList(),
        ): ListViewState {
            val sortKey = request.queryParam("sort", defaultSort.column).let { if (it in sortable) it else defaultSort.column }
            val defaultDir = if (defaultSort.direction == SortDirection.ASC) "asc" else "desc"
            val direction =
                if (request.queryParam("dir", defaultDir).equals("asc", ignoreCase = true)) SortDirection.ASC else SortDirection.DESC
            val defaultSize = pageSizes.first()
            val size = request.queryParamInt("size", defaultSize).let { if (it in pageSizes) it else defaultSize }
            val page = request.queryParamInt("page", 1).coerceAtLeast(1)

            // Insertion order = filterNames order, so the canonical URL is deterministic.
            val filters = LinkedHashMap<String, String>()
            filterNames.forEach { name ->
                request.queryParam(name)?.ifBlank { null }?.let { filters[name] = it }
            }

            return ListViewState(basePath, sortKey, direction, size, page, filters)
        }
    }
}

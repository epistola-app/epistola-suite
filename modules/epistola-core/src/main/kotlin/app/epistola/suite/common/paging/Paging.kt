package app.epistola.suite.common.paging

/**
 * Shared, surface-agnostic paging/sorting primitives for CQRS list queries.
 *
 * They live in `epistola-core` (not `epistola-web`) because a core query *returns*
 * [PagedResult]; the UI handler, REST API, and MCP server all depend on core and
 * therefore can all see these types. See ADR 0007.
 */

enum class SortDirection { ASC, DESC }

/**
 * A request to sort by a *logical* column key (e.g. "name", "updated") — never a
 * raw SQL expression. Each query maps allowed keys to fixed SQL in its own
 * whitelist; unknown keys fall back to the query's default sort.
 */
data class SortSpec(val column: String, val direction: SortDirection)

/**
 * A 1-based page request. Fails fast on out-of-range bounds at construction, so a
 * bad value never reaches the handler or SQL. Untrusted input (URL query params)
 * must be clamped to the allowed page sizes *before* constructing one — see ADR 0007.
 */
data class PageRequest(val page: Int, val size: Int) {
    init {
        require(page >= 1) { "page must be >= 1, was $page" }
        require(size >= 1) { "size must be >= 1, was $size" }
    }
}

/**
 * One page of [items] plus the [total] count of the full filtered set (before the
 * page window). Modeled on Strapi's `meta.pagination` envelope (ADR 0007).
 */
data class PagedResult<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val total: Long,
) {
    /** Total number of pages; at least 1 even when empty. */
    val totalPages: Int get() = if (total == 0L) 1 else ((total + size - 1) / size).toInt()

    /** 1-based index of the first item on this page (0 when empty). For "Showing 1–50 of N". */
    val from: Long get() = if (total == 0L) 0 else ((page - 1).toLong() * size) + 1

    /** 1-based index of the last item on this page (0 when empty). */
    val to: Long get() = minOf(page.toLong() * size, total)
}

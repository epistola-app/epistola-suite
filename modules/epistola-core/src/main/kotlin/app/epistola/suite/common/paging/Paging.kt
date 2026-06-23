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

    companion object {
        /**
         * The whole result set in one page — for internal/non-UI callers (REST list
         * endpoints, counts, background jobs) that genuinely want every row, not a window.
         * The UI always passes a real, clamped page size; this is the opt-out for code
         * that must not be capped at a default. See ADR 0007.
         */
        val ALL = PageRequest(page = 1, size = Int.MAX_VALUE)
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

/**
 * A per-query allow-list mapping *logical* sort keys (the keys the UI and URL use,
 * e.g. "name", "updated") to fixed SQL expressions (e.g. "dt.name"). `ORDER BY`
 * cannot be a bind parameter, so an unknown/forged key must never reach SQL: it
 * falls back to [default]. Direction is rendered from the [SortDirection] enum,
 * never from user text. See ADR 0007.
 *
 * Used together with [pagedQuery] so a query declares its sortable surface in one
 * place instead of hand-rolling the whitelist + ORDER BY string each time.
 */
class SortWhitelist(
    private val columns: Map<String, String>,
    val default: SortSpec,
) {
    init {
        require(columns.containsKey(default.column)) {
            "default sort column '${default.column}' must be one of the whitelisted keys ${columns.keys}"
        }
    }

    /** The whitelisted keys, for surfacing the sortable columns to a caller if needed. */
    val keys: Set<String> get() = columns.keys

    /**
     * Render a safe `ORDER BY` body for [requested], falling back to [default] when the
     * requested key is not whitelisted. [tiebreaker] is appended `ASC` to keep offset
     * paging deterministic when the sort column ties (e.g. the primary key).
     */
    fun orderBy(requested: SortSpec, tiebreaker: String): String {
        val spec = if (columns.containsKey(requested.column)) requested else default
        val column = columns.getValue(spec.column)
        val direction = if (spec.direction == SortDirection.ASC) "ASC" else "DESC"
        return "$column $direction, $tiebreaker ASC"
    }
}

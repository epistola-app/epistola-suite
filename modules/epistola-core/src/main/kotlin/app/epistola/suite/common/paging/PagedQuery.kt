package app.epistola.suite.common.paging

import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.kotlin.mapTo
import org.jdbi.v3.core.statement.Query
import java.sql.ResultSet

/**
 * Assembles a [PagedResult] from a per-row stream of `(item, totalCount)` pairs, owning the
 * stale-deep-link clamp every list query otherwise copy-pastes (ADR 0007): a windowed
 * `COUNT(*) OVER()` returns *no rows* on an out-of-range page, so when [fetch] comes back
 * empty past page 1 we re-fetch page 1 to learn the total, clamp to the last page, and fetch
 * that — keeping a correct total and non-empty rows on a stale bookmark.
 *
 * [fetch] takes a 0-based offset and returns the page's rows, each paired with the windowed
 * total (the same value on every row). The two [pagedQuery] overloads wrap this with the row
 * mapping; call this directly only for a bespoke fetch.
 */
fun <T> pagedResult(page: PageRequest, fetch: (offset: Int) -> List<Pair<T, Long>>): PagedResult<T> {
    val size = page.size
    var pageNumber = page.page
    var rows = fetch((pageNumber - 1) * size)

    if (rows.isEmpty() && pageNumber > 1) {
        val firstPage = fetch(0)
        val total = firstPage.firstOrNull()?.second ?: 0L
        val lastPage = if (total == 0L) 1 else ((total + size - 1) / size).toInt()
        pageNumber = lastPage
        rows = when {
            total == 0L -> emptyList()
            lastPage == 1 -> firstPage
            else -> fetch((lastPage - 1) * size)
        }
    }

    return PagedResult(
        items = rows.map { it.first },
        page = pageNumber,
        size = size,
        total = rows.firstOrNull()?.second ?: 0L,
    )
}

/**
 * Runs a windowed, offset-paginated list query whose rows map to a reified [ROW] type via
 * JDBI's Kotlin mapper (incl. `@Nested`/`@Json`), then projects each to the domain type [T].
 * The row [sql] must select `COUNT(*) OVER() AS …` and end with `LIMIT :limit OFFSET :offset`;
 * the caller binds its own filter params via [bind] (this helper binds only `limit`/`offset`).
 *
 * ```
 * handle.pagedQuery<ThemeRow, Theme>(sql, query.page,
 *     bind = { it.bind("tenantId", tenantKey) }, totalOf = { it.totalCount }, map = { it.theme })
 * ```
 */
inline fun <reified ROW : Any, T> Handle.pagedQuery(
    sql: String,
    page: PageRequest,
    crossinline bind: (Query) -> Query = { it },
    crossinline totalOf: (ROW) -> Long,
    crossinline map: (ROW) -> T,
): PagedResult<T> = pagedResult(page) { offset ->
    bind(createQuery(sql))
        .bind("limit", page.size)
        .bind("offset", offset)
        .mapTo<ROW>()
        .list()
        .map { map(it) to totalOf(it) }
}

/**
 * Same contract as the reified overload, but for queries whose rows are read with a hand-written
 * [ResultSet] mapper (e.g. a column decode JDBI can't do reflectively). [mapRow] builds the
 * domain object from the row; the windowed total is read from [totalColumn].
 *
 * ```
 * handle.pagedQuery(sql, query.page,
 *     bind = { it.bind("tenantId", tenantKey) }, mapRow = { it.toApiKey(withDisplayName = true) })
 * ```
 */
inline fun <T> Handle.pagedQuery(
    sql: String,
    page: PageRequest,
    totalColumn: String = "total_count",
    crossinline bind: (Query) -> Query = { it },
    crossinline mapRow: (ResultSet) -> T,
): PagedResult<T> = pagedResult(page) { offset ->
    bind(createQuery(sql))
        .bind("limit", page.size)
        .bind("offset", offset)
        .map { rs, _ -> mapRow(rs) to rs.getLong(totalColumn) }
        .list()
}

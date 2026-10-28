// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.common

import org.jdbi.v3.core.mapper.RowMapper
import org.jdbi.v3.core.statement.Query

/** One page of a list, and [total], how many rows match in all (ignoring paging). */
data class ResultPage<T>(val items: List<T>, val total: Long)

/** Which rows of a list to read: [limit] of them, after skipping [offset], in the list's own order. */
data class PageRequest(val limit: Int, val offset: Int)

/**
 * Paging a list query in the database, never by slicing a list fetched in full: the page costs the
 * page, not the table.
 *
 * The query selects [TOTAL] beside its columns, so the page and the count come from one statement and
 * cannot disagree, and ends in [LIMIT_OFFSET] after its `ORDER BY`. A page past the end has no row to
 * carry the count on, so that one case reads the count from the query's first row.
 */
object Paging {
    /** The select-list column carrying the number of all matching rows. */
    const val TOTAL: String = "COUNT(*) OVER ()::bigint AS paging_total"

    /** The query's last clause; binds `limit` and `offset`. */
    const val LIMIT_OFFSET: String = "LIMIT :limit OFFSET :offset"

    /**
     * Reads [page] of the query [statement] builds, each row mapped by [mapper]. [statement] is called
     * again for the count when the page is past the end, so it must build the same query each time.
     */
    fun <T> read(page: PageRequest, mapper: RowMapper<T>, statement: () -> Query): ResultPage<T> {
        val rows = run(statement(), page, mapper)
        if (rows.isNotEmpty() || page.offset <= 0) return ResultPage(rows.map { it.row }, rows.firstOrNull()?.total ?: 0L)
        val total = run(statement(), PageRequest(limit = 1, offset = 0), mapper).firstOrNull()?.total ?: 0L
        return ResultPage(emptyList(), total)
    }

    /** [read] for rows Jdbi maps to [type] on its own, as `mapTo<T>()` does. */
    fun <T : Any> read(page: PageRequest, type: Class<T>, statement: () -> Query): ResultPage<T> = read(
        page,
        RowMapper { rs, ctx -> ctx.findRowMapperFor(type).orElseThrow { IllegalStateException("no row mapper for $type") }.map(rs, ctx) },
        statement,
    )

    private class Counted<T>(val row: T, val total: Long)

    private fun <T> run(query: Query, page: PageRequest, mapper: RowMapper<T>): List<Counted<T>> = query
        .bind("limit", page.limit.coerceAtLeast(1))
        .bind("offset", page.offset.coerceAtLeast(0))
        .map { rs, ctx -> Counted(mapper.map(rs, ctx), rs.getLong("paging_total")) }
        .list()
}

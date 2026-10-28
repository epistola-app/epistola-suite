// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.api.model.PageMeta
import app.epistola.suite.common.PageRequest

/**
 * Pagination helpers for the v1 REST list endpoints.
 *
 * The contract exposes `page` (0-based) and `size` query parameters and wraps every
 * collection response in `{ items, page: PageMeta }`. These helpers build the
 * [PageMeta] and translate page/size into SQL `LIMIT`/`OFFSET`, coercing `size`
 * into the contract's allowed range so a hostile or absent value can't escape it.
 *
 * Every list is paged in the database, never by slicing a list fetched in full: a
 * `…Page` query takes [requestOf] and answers one page and its total, [pageMeta] makes
 * the envelope of it. [limitOf] + [offsetOf] serve the queries that take the two apart.
 */
object Pagination {
    const val DEFAULT_PAGE_SIZE = 20
    const val MAX_PAGE_SIZE = 100

    /** Clamp `size` into the contract's 1..MAX range; the SQL `LIMIT`. */
    fun limitOf(size: Int): Int = size.coerceIn(1, MAX_PAGE_SIZE)

    /** The SQL `OFFSET` for a 0-based `page` at the clamped page size. */
    fun offsetOf(page: Int, size: Int): Int = page.coerceAtLeast(0).toLong().times(limitOf(size)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** Build the response [PageMeta] from the requested page/size and the true total. */
    fun pageMeta(page: Int, size: Int, totalElements: Long): PageMeta {
        val safeSize = limitOf(size)
        val totalPages = if (totalElements <= 0L) 0 else ((totalElements + safeSize - 1) / safeSize).toInt()
        return PageMeta(
            number = page.coerceAtLeast(0),
            propertySize = safeSize,
            total = totalElements,
            totalPages = totalPages,
        )
    }

    /** The rows a 0-based `page` of `size` asks for, clamped as [limitOf] and [offsetOf]. */
    fun requestOf(page: Int, size: Int): PageRequest = PageRequest(limit = limitOf(size), offset = offsetOf(page, size))
}

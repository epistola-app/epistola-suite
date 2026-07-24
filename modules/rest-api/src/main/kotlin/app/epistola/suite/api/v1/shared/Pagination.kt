// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.api.model.PageMeta

/**
 * Pagination helpers for the v1 REST list endpoints.
 *
 * The contract exposes `page` (0-based) and `size` query parameters and wraps every
 * collection response in `{ items, page: PageMeta }`. These helpers build the
 * [PageMeta] and translate page/size into SQL `LIMIT`/`OFFSET`, coercing `size`
 * into the contract's allowed range so a hostile or absent value can't escape it.
 *
 * Two strategies, both producing an identical envelope:
 *  - [paginate] — slice an already-materialised list in application code. Use for
 *    the bounded per-tenant/per-catalog config collections (variants, versions,
 *    activations, …) where fetching the full list is cheap and the query is shared
 *    with internal callers that need every row.
 *  - [limitOf] + [offsetOf] + [pageMeta] — push `LIMIT`/`OFFSET` into the query and
 *    obtain the total from a sibling `Count…` query. Use for the large, unbounded
 *    history tables (documents, generation jobs) where materialising everything is
 *    not acceptable.
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
            totalElements = totalElements,
            totalPages = totalPages,
        )
    }

    /** One page sliced from a fully-materialised list, plus the matching [PageMeta]. */
    data class Slice<T>(val items: List<T>, val page: PageMeta)

    /**
     * Slice [all] to the requested page in application code. `totalElements` is the
     * full (already-filtered) list size, so callers must apply any filtering before
     * calling this.
     */
    fun <T> paginate(all: List<T>, page: Int, size: Int): Slice<T> {
        val safeSize = limitOf(size)
        val from = offsetOf(page, size).coerceAtMost(all.size)
        val to = (from.toLong() + safeSize).coerceAtMost(all.size.toLong()).toInt()
        return Slice(all.subList(from, to), pageMeta(page, size, all.size.toLong()))
    }
}

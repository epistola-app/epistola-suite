// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.model

/**
 * One page of the template versions that use a stencil. Pagination and the count are computed in
 * SQL (see `GetStencilUsagePage`); whether each row fits is computed for the rows on the page.
 *
 * @property items the rows on this page.
 * @property page the 1-based current page (clamped to a valid range).
 * @property totalPages total pages.
 * @property total rows across all pages.
 */
data class StencilUsagePage(
    val items: List<StencilUsageDetail>,
    val page: Int,
    val totalPages: Int,
    val total: Int,
)

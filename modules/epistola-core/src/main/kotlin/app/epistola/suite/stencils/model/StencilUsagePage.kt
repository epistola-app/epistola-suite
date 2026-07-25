// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.model

/**
 * One page of stencil-usage rows for the bulk-upgrade table, with the counts the
 * UI needs. Filtering, the per-variant `upgradable` flag, pagination and the
 * counts are all computed in SQL (see `GetStencilUsagePage`) rather than by
 * loading the whole usage set into memory.
 *
 * @property items the rows on this page (already filtered + sliced).
 * @property page the 1-based current page (clamped to a valid range).
 * @property totalPages total pages for the active filter.
 * @property total rows matching the active filter (across all pages).
 * @property totalAll all usage rows regardless of filter.
 * @property upgradableCount upgradable rows regardless of filter.
 * @property filter the active filter: `both`, `upgradable`, or `not-upgradable`.
 */
data class StencilUsagePage(
    val items: List<StencilUsageDetail>,
    val page: Int,
    val totalPages: Int,
    val total: Int,
    val totalAll: Int,
    val upgradableCount: Int,
    val filter: String,
)

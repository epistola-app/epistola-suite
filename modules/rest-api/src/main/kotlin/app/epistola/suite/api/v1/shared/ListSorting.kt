// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

/**
 * Helpers for the `sort` / `direction` query parameters that the v1 contract advertises on
 * every list endpoint even though only a subset currently sort.
 */
object ListSorting {
    /**
     * Validate `sort`/`direction` for a list endpoint that exposes no sortable columns.
     *
     * The contract advertises both params on every list operation, so a caller may send them
     * here too — but this endpoint can honor no sort key, so a non-null [sort] is rejected with
     * an empty supported set (a 400 via [UnsupportedSortException]) rather than silently ignored;
     * this way a caller can tell "sorting isn't supported here" from "sorting was applied".
     * [direction] is validated the same way it is on the sortable endpoints (unknown non-null →
     * 400) so malformed input fails loudly everywhere; the resolved value is unused because there
     * is no ordering for it to apply to. An absent [sort] and the default `desc` pass untouched.
     */
    fun rejectUnsupportedSort(sort: String?, direction: String) {
        if (sort != null) throw UnsupportedSortException(sort, emptyList())
        SortDirection.fromParam(direction)
    }
}

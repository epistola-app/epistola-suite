// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

/**
 * Sort direction for the v1 REST list endpoints, resolved from the `direction` query
 * parameter. Mirrors the `…Sort.fromParam` idiom used for the sort column so both halves
 * of a sortable endpoint validate the same way: an absent value falls back to [DESC] (the
 * contract default), while an unrecognized non-null value is rejected with a 400 rather
 * than silently reinterpreted.
 *
 * @property param The stable query-parameter value.
 * @property descending Whether this direction maps to SQL `DESC`.
 */
enum class SortDirection(
    val param: String,
    val descending: Boolean,
) {
    ASC("asc", false),
    DESC("desc", true),
    ;

    companion object {
        /** The supported wire values, for enumerating in a rejection when an unknown value is supplied. */
        val paramValues: List<String> = entries.map { it.param }

        /**
         * Case-insensitive. An absent (`null`) value selects the [DESC] default; an unrecognized
         * non-null value is rejected with [UnsupportedSortDirectionException] (mapped to a 400),
         * matching how an unknown sort key is rejected rather than silently ignored.
         */
        fun fromParam(param: String?): SortDirection {
            if (param == null) return DESC
            return entries.find { it.param.equals(param, ignoreCase = true) }
                ?: throw UnsupportedSortDirectionException(param, paramValues)
        }
    }
}

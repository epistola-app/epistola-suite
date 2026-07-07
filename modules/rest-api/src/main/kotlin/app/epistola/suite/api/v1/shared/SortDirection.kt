package app.epistola.suite.api.v1.shared

/**
 * Sort direction for the v1 REST list endpoints, resolved from the `direction` query
 * parameter. Mirrors the `…Sort.fromParam` idiom used for the sort column so both
 * halves of a sortable endpoint read the same way: an unrecognized (or absent) value
 * falls back to [DESC], the contract default, rather than being rejected.
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
        /** Case-insensitive; unknown values fall back to [DESC], matching the contract default. */
        fun fromParam(param: String?): SortDirection = entries.find { it.param.equals(param, ignoreCase = true) } ?: DESC
    }
}

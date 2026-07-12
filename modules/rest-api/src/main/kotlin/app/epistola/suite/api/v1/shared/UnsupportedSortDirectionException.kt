package app.epistola.suite.api.v1.shared

/**
 * Thrown when a v1 list endpoint receives a `direction` query parameter whose value is not one of
 * the supported sort directions (`asc` / `desc`).
 *
 * The v1 contract declares `direction` as a free-form string with a default of `desc` and no
 * server-side validation, so an unrecognized non-null value is rejected here — HTTP 400 via
 * [app.epistola.suite.api.v1.ApiProblemTypes.UNSUPPORTED_SORT_DIRECTION] — rather than being
 * silently reinterpreted as the default. This mirrors how an unknown sort key is rejected (see
 * [UnsupportedSortException]) so both halves of a sortable endpoint fail loudly on bad input. An
 * absent `direction` is never an error: it selects the default `desc`.
 *
 * @property value The unrecognized `direction` value the caller sent.
 * @property supportedValues The supported directions, in declaration order (`asc`, `desc`).
 */
class UnsupportedSortDirectionException(
    val value: String,
    val supportedValues: List<String>,
) : RuntimeException("Unsupported sort direction '$value'; supported: ${supportedValues.joinToString(", ")}")

package app.epistola.suite.api.v1.shared

/**
 * Thrown when a v1 list endpoint receives a `sort` query parameter whose value is not one of
 * the endpoint's supported sort keys.
 *
 * The v1 contract declares `sort` as a free-form, optional string with no server-side validation
 * (no enum, no pattern), so an unsupported key is rejected here — HTTP 400 via
 * [app.epistola.suite.api.v1.ApiProblemTypes.UNSUPPORTED_SORT] — rather than silently ignored. The
 * error body carries [supportedValues], which is the only place callers can discover the valid keys
 * now that the contract no longer advertises them. An absent `sort` is never an error: it selects
 * the endpoint's default order.
 *
 * @property value The unrecognized `sort` value the caller sent.
 * @property supportedValues The endpoint's whitelisted sort keys, in declaration order.
 */
class UnsupportedSortException(
    val value: String,
    val supportedValues: List<String>,
) : RuntimeException("Unsupported sort '$value'; supported: ${supportedValues.joinToString(", ")}")

package app.epistola.suite.validation

/**
 * Exception thrown when command validation fails.
 *
 * @property field The name of the field that failed validation
 * @property message The validation error message
 */
class ValidationException(
    val field: String,
    override val message: String,
) : IllegalArgumentException(message)

/**
 * Validates that a condition is true, throwing [ValidationException] if not.
 *
 * @param field The name of the field being validated
 * @param value The condition that must be true
 * @param lazyMessage A function that produces the error message
 */
inline fun validate(field: String, value: Boolean, lazyMessage: () -> String) {
    if (!value) {
        throw ValidationException(field, lazyMessage())
    }
}

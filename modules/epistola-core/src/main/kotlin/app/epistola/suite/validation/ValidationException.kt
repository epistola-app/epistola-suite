package app.epistola.suite.validation

/**
 * Exception thrown when command validation fails.
 *
 * @property field The name of the field that failed validation
 * @property message The validation error message (human-readable only — the
 *   machine-readable identity lives in [code], not as a message prefix)
 * @property code Machine-readable error code; defaults to [ValidationCode.GENERIC]
 *   so the many non-specific validation sites need no change
 */
class ValidationException(
    val field: String,
    override val message: String,
    val code: ValidationCode = ValidationCode.GENERIC,
) : IllegalArgumentException(message)

/**
 * Validates that a condition is true, throwing [ValidationException] if not.
 *
 * @param field The name of the field being validated
 * @param value The condition that must be true
 * @param code Machine-readable error code (defaults to [ValidationCode.GENERIC])
 * @param lazyMessage A function that produces the error message
 */
inline fun validate(
    field: String,
    value: Boolean,
    code: ValidationCode = ValidationCode.GENERIC,
    lazyMessage: () -> String,
) {
    if (!value) {
        throw ValidationException(field, lazyMessage(), code)
    }
}

package app.epistola.suite.api.v1

import app.epistola.api.model.FieldError
import app.epistola.api.model.ValidationErrorResponse
import app.epistola.suite.validation.ValidationException

/**
 * Single translation point from a [ValidationException] to the wire
 * [ValidationErrorResponse]. The editor draft-save UI route still consumes this
 * pre-RFC 7807 shape, so keep it separate from the REST API Problem Details
 * mapper in [ValidationException.toValidationProblemBody].
 *
 * The historical generic `"VALIDATION_ERROR"` wire value is preserved for
 * non-coded sites via [app.epistola.suite.validation.ValidationCode.GENERIC];
 * coded sites now emit their specific code instead of being flattened.
 *
 * Do not change this mapper to Problem Details unless the editor/UI consumer is
 * migrated at the same time.
 */
fun ValidationException.toValidationErrorResponse(): ValidationErrorResponse = ValidationErrorResponse(
    code = code.wire,
    message = message,
    errors = listOf(
        FieldError(
            field = field,
            message = message,
            rejectedValue = null,
        ),
    ),
)

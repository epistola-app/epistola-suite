package app.epistola.suite.api.v1

import app.epistola.api.model.ValidationError
import app.epistola.suite.validation.ValidationException

/**
 * Wire response for the editor draft-save UI route.
 *
 * This is the pre-Problem-Details shape (`code` / `message` / `errors[]`) that the
 * editor still consumes. The contract no longer ships a `ValidationErrorResponse`
 * type (it adopted RFC 9457 Problem Details), so the envelope is defined locally
 * here; the field-level entries reuse the contract's [ValidationError].
 *
 * Keep this separate from the REST API Problem Details mapper in
 * [ValidationException.toValidationProblemBody]. Do not change this mapper to
 * Problem Details unless the editor/UI consumer is migrated at the same time.
 */
data class DraftValidationErrorResponse(
    val code: String,
    val message: String,
    val errors: List<ValidationError>,
)

/**
 * Single translation point from a [ValidationException] to the editor's
 * [DraftValidationErrorResponse] wire shape.
 *
 * The historical generic `"VALIDATION_ERROR"` wire value is preserved for
 * non-coded sites via [app.epistola.suite.validation.ValidationCode.GENERIC];
 * coded sites now emit their specific code instead of being flattened.
 */
fun ValidationException.toValidationErrorResponse(): DraftValidationErrorResponse = DraftValidationErrorResponse(
    code = code.wire,
    message = message,
    errors = listOf(
        ValidationError(
            field = field,
            message = message,
            rejectedValue = null,
        ),
    ),
)

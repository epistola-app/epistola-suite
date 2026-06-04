package app.epistola.suite.api.v1

import app.epistola.api.model.ValidationError
import app.epistola.suite.validation.ValidationException

/**
 * Wire response for the editor draft-save UI route.
 *
 * A small envelope the editor consumes: the problem `type` URI (the machine-readable
 * discriminator the editor switches on — same value the REST API exposes as
 * `ProblemDetail.type`), a human-readable `message`, and field-level `errors[]`.
 *
 * This is intentionally NOT full `application/problem+json` — it stays a plain JSON
 * envelope tailored to the editor. Keep it in sync with the REST API discriminator
 * (the `type` URI) rather than the removed `code` member.
 */
data class DraftValidationErrorResponse(
    val type: String,
    val message: String,
    val errors: List<ValidationError>,
)

/**
 * Single translation point from a [ValidationException] to the editor's
 * [DraftValidationErrorResponse] wire shape.
 *
 * The `type` URI is derived from the validation code via the shared
 * [ApiProblemTypes] registry, so the editor sees the same discriminator the REST
 * surface emits (e.g. `https://epistola.app/errors/node-parameter-binding-syntax-invalid`).
 */
fun ValidationException.toValidationErrorResponse(): DraftValidationErrorResponse = DraftValidationErrorResponse(
    type = ApiProblemTypes.validationProblemType(code).type.toString(),
    message = message,
    errors = listOf(
        ValidationError(
            field = field,
            message = message,
            rejectedValue = null,
        ),
    ),
)

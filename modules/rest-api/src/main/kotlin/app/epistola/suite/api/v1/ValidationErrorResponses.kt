package app.epistola.suite.api.v1

import app.epistola.api.model.FieldError
import app.epistola.api.model.ValidationErrorResponse
import app.epistola.suite.validation.ValidationException

/**
 * Single translation point from a [ValidationException] to the wire
 * [ValidationErrorResponse]. Every surface that surfaces validation failures
 * funnels through here so the machine-readable [ValidationException.code] is
 * reported consistently (REST `ApiExceptionHandler`, the UI draft-save route).
 *
 * The historical generic `"VALIDATION_ERROR"` wire value is preserved for
 * non-coded sites via [app.epistola.suite.validation.ValidationCode.GENERIC];
 * coded sites now emit their specific code instead of being flattened.
 *
 * RFC 7807 — this is the seam. The next step is to return
 * `org.springframework.http.ProblemDetail` (`application/problem+json`) with
 * `type` derived from `ex.code.wire`, `title` from the code, `status` from the
 * surface, `detail` = `ex.message`, and `errors` as an extension member. Because
 * both surfaces call this one function, that swap is local to this file.
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

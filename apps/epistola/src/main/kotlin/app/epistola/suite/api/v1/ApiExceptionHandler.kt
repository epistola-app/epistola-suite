package app.epistola.suite.api.v1

import app.epistola.api.model.FieldError
import app.epistola.api.model.ValidationErrorResponse
import app.epistola.suite.documents.commands.BatchValidationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Global exception handler for REST API controllers.
 */
@RestControllerAdvice(basePackages = ["app.epistola.suite.api.v1"])
class ApiExceptionHandler {

    @ExceptionHandler(BatchValidationException::class)
    fun handleBatchValidationException(ex: BatchValidationException): ResponseEntity<ValidationErrorResponse> {
        val errors = mutableListOf<FieldError>()

        ex.duplicateCorrelationIds.forEach { correlationId ->
            errors.add(
                FieldError(
                    field = "correlationId",
                    message = "Duplicate correlationId in batch: $correlationId",
                    rejectedValue = correlationId,
                ),
            )
        }

        ex.duplicateFilenames.forEach { filename ->
            errors.add(
                FieldError(
                    field = "filename",
                    message = "Duplicate filename in batch: $filename",
                    rejectedValue = filename,
                ),
            )
        }

        val response = ValidationErrorResponse(
            code = "BATCH_VALIDATION_ERROR",
            message = ex.message ?: "Batch validation failed",
            errors = errors,
        )

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response)
    }
}

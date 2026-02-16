package app.epistola.suite.api.v1

import app.epistola.api.model.FieldError
import app.epistola.api.model.ValidationErrorResponse
import app.epistola.suite.documents.commands.BatchValidationException
import app.epistola.suite.templates.commands.variants.DefaultVariantDeletionException
import app.epistola.suite.templates.commands.versions.VersionStillActiveException
import app.epistola.suite.templates.validation.DataModelValidationException
import app.epistola.suite.themes.LastThemeException
import app.epistola.suite.themes.ThemeInUseException
import app.epistola.suite.themes.ThemeNotFoundException
import app.epistola.suite.validation.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Standard error response structure for API errors.
 */
data class ApiErrorResponse(
    val code: String,
    val message: String,
    val details: Map<String, Any?>? = null,
)

/**
 * Global exception handler for REST API controllers.
 * Provides consistent error responses across all API endpoints.
 */
@RestControllerAdvice(basePackages = ["app.epistola.suite.api.v1"])
class ApiExceptionHandler {

    private val logger = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    @ExceptionHandler(BatchValidationException::class)
    fun handleBatchValidationException(ex: BatchValidationException): ResponseEntity<ValidationErrorResponse> {
        logger.warn("Batch validation failed: {}", ex.message)

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

    /**
     * Handles theme not found errors.
     * Returns 404 Not Found.
     */
    @ExceptionHandler(ThemeNotFoundException::class)
    fun handleThemeNotFoundException(ex: ThemeNotFoundException): ResponseEntity<ApiErrorResponse> {
        logger.warn("Theme not found: {}", ex.themeId)

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiErrorResponse(
                code = "THEME_NOT_FOUND",
                message = ex.message ?: "Theme not found",
                details = mapOf("themeId" to ex.themeId.value),
            ),
        )
    }

    /**
     * Handles attempts to delete a theme that is in use.
     * Returns 409 Conflict.
     */
    @ExceptionHandler(ThemeInUseException::class)
    fun handleThemeInUseException(ex: ThemeInUseException): ResponseEntity<ApiErrorResponse> {
        logger.warn("Theme in use, cannot delete: {}", ex.themeId)

        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiErrorResponse(
                code = "THEME_IN_USE",
                message = ex.message ?: "Theme is in use and cannot be deleted",
                details = mapOf("themeId" to ex.themeId.value),
            ),
        )
    }

    /**
     * Handles attempts to delete the last theme for a tenant.
     * Returns 400 Bad Request.
     */
    @ExceptionHandler(LastThemeException::class)
    fun handleLastThemeException(ex: LastThemeException): ResponseEntity<ApiErrorResponse> {
        logger.warn("Cannot delete last theme: {}", ex.themeId)

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(
                code = "LAST_THEME",
                message = ex.message ?: "Cannot delete the last theme for a tenant",
                details = mapOf("themeId" to ex.themeId.value),
            ),
        )
    }

    /**
     * Handles attempts to delete the default variant.
     * Returns 409 Conflict.
     */
    @ExceptionHandler(DefaultVariantDeletionException::class)
    fun handleDefaultVariantDeletionException(ex: DefaultVariantDeletionException): ResponseEntity<ApiErrorResponse> {
        logger.warn("Cannot delete default variant: {}", ex.variantId)

        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiErrorResponse(
                code = "DEFAULT_VARIANT_DELETION",
                message = ex.message ?: "Cannot delete the default variant",
                details = mapOf("variantId" to ex.variantId.value),
            ),
        )
    }

    /**
     * Handles attempts to archive a version that is still active in environments.
     * Returns 409 Conflict.
     */
    @ExceptionHandler(VersionStillActiveException::class)
    fun handleVersionStillActiveException(ex: VersionStillActiveException): ResponseEntity<ApiErrorResponse> {
        logger.warn("Cannot archive version {}: still active in environments", ex.versionId)

        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiErrorResponse(
                code = "VERSION_STILL_ACTIVE",
                message = ex.message ?: "Version is still active in one or more environments",
                details = mapOf(
                    "versionId" to ex.versionId.value,
                    "variantId" to ex.variantId.value,
                    "activeEnvironments" to ex.activeEnvironments.map { it.value },
                ),
            ),
        )
    }

    /**
     * Handles data model validation errors (schema validation failures).
     * Returns 422 Unprocessable Entity.
     */
    @ExceptionHandler(DataModelValidationException::class)
    fun handleDataModelValidationException(ex: DataModelValidationException): ResponseEntity<ApiErrorResponse> {
        logger.warn("Data model validation failed: {} examples with errors", ex.validationErrors.size)

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
            ApiErrorResponse(
                code = "DATA_MODEL_VALIDATION_ERROR",
                message = "Data examples failed validation against schema",
                details = mapOf("validationErrors" to ex.validationErrors),
            ),
        )
    }

    /**
     * Handles general validation errors (e.g., invalid field values).
     * Returns 400 Bad Request.
     */
    @ExceptionHandler(ValidationException::class)
    fun handleValidationException(ex: ValidationException): ResponseEntity<ValidationErrorResponse> {
        logger.warn("Validation failed for field '{}': {}", ex.field, ex.message)

        val response = ValidationErrorResponse(
            code = "VALIDATION_ERROR",
            message = ex.message,
            errors = listOf(
                FieldError(
                    field = ex.field,
                    message = ex.message,
                    rejectedValue = null,
                ),
            ),
        )

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response)
    }

    /**
     * Handles unexpected exceptions.
     * Returns 500 Internal Server Error.
     */
    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): ResponseEntity<ApiErrorResponse> {
        logger.error("Unexpected error in API controller", ex)

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ApiErrorResponse(
                code = "INTERNAL_ERROR",
                message = "An unexpected error occurred",
            ),
        )
    }
}

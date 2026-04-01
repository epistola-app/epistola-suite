package app.epistola.suite.api.v1

import app.epistola.api.model.FieldError
import app.epistola.api.model.ValidationErrorResponse
import app.epistola.suite.assets.AssetInUseException
import app.epistola.suite.assets.AssetNotFoundException
import app.epistola.suite.assets.AssetTooLargeException
import app.epistola.suite.assets.UnsupportedAssetTypeException
import app.epistola.suite.attributes.commands.AllowedValuesInUseException
import app.epistola.suite.attributes.commands.AttributeInUseException
import app.epistola.suite.documents.DefaultVariantNotFoundException
import app.epistola.suite.documents.EnvironmentNotFoundException
import app.epistola.suite.documents.TemplateVariantNotFoundException
import app.epistola.suite.documents.VersionNotFoundException
import app.epistola.suite.documents.commands.BatchValidationException
import app.epistola.suite.environments.EnvironmentInUseException
import app.epistola.suite.security.PermissionDeniedException
import app.epistola.suite.security.PlatformAccessDeniedException
import app.epistola.suite.security.TenantAccessDeniedException
import app.epistola.suite.templates.commands.variants.DefaultVariantDeletionException
import app.epistola.suite.templates.commands.versions.VersionStillActiveException
import app.epistola.suite.templates.services.AmbiguousVariantResolutionException
import app.epistola.suite.templates.services.NoMatchingVariantException
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
     * Handles variant resolution failures when no matching variant is found.
     * Returns 404 Not Found.
     */
    @ExceptionHandler(NoMatchingVariantException::class)
    fun handleNoMatchingVariantException(ex: NoMatchingVariantException): ResponseEntity<ApiErrorResponse> {
        logger.warn("No matching variant for template {}: {}", ex.templateId, ex.criteria)

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiErrorResponse(
                code = "NO_MATCHING_VARIANT",
                message = ex.message ?: "No matching variant found",
                details = mapOf(
                    "templateId" to ex.templateId.value,
                    "requiredAttributes" to ex.criteria.requiredAttributes,
                ),
            ),
        )
    }

    /**
     * Handles ambiguous variant resolution when multiple variants tie on score.
     * Returns 409 Conflict.
     */
    @ExceptionHandler(AmbiguousVariantResolutionException::class)
    fun handleAmbiguousVariantResolutionException(ex: AmbiguousVariantResolutionException): ResponseEntity<ApiErrorResponse> {
        logger.warn("Ambiguous variant resolution for template {}: {}", ex.templateId, ex.tiedVariantIds)

        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiErrorResponse(
                code = "AMBIGUOUS_VARIANT",
                message = ex.message ?: "Ambiguous variant resolution",
                details = mapOf(
                    "templateId" to ex.templateId.value,
                    "tiedVariants" to ex.tiedVariantIds.map { it.value },
                ),
            ),
        )
    }

    /**
     * Handles attempts to delete an attribute that is still referenced by variants.
     * Returns 409 Conflict.
     */
    @ExceptionHandler(AttributeInUseException::class)
    fun handleAttributeInUseException(ex: AttributeInUseException): ResponseEntity<ApiErrorResponse> {
        logger.warn("Cannot delete attribute {}: still in use by {} variant(s)", ex.attributeId, ex.variantCount)

        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiErrorResponse(
                code = "ATTRIBUTE_IN_USE",
                message = ex.message ?: "Attribute is in use and cannot be deleted",
                details = mapOf(
                    "attributeId" to ex.attributeId.key,
                    "variantCount" to ex.variantCount,
                ),
            ),
        )
    }

    /**
     * Handles attempts to narrow allowed values when existing variants use the removed values.
     * Returns 409 Conflict.
     */
    @ExceptionHandler(AllowedValuesInUseException::class)
    fun handleAllowedValuesInUseException(ex: AllowedValuesInUseException): ResponseEntity<ApiErrorResponse> {
        logger.warn("Cannot narrow allowed values for attribute {}: values {} are in use", ex.attributeId, ex.removedValues)

        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiErrorResponse(
                code = "ALLOWED_VALUES_IN_USE",
                message = ex.message ?: "Cannot remove allowed values that are in use by existing variants",
                details = mapOf(
                    "attributeId" to ex.attributeId.key,
                    "valuesInUse" to ex.removedValues,
                ),
            ),
        )
    }

    /**
     * Handles template/variant not found during document generation.
     * Returns 404 Not Found.
     */
    @ExceptionHandler(TemplateVariantNotFoundException::class)
    fun handleTemplateVariantNotFoundException(ex: TemplateVariantNotFoundException): ResponseEntity<ApiErrorResponse> {
        logger.warn("Template variant not found: {}", ex.message)

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiErrorResponse(
                code = "TEMPLATE_VARIANT_NOT_FOUND",
                message = ex.message ?: "Template variant not found",
                details = mapOf(
                    "tenantId" to ex.tenantId.value,
                    "templateId" to ex.templateId.value,
                    "variantId" to ex.variantId.value,
                ),
            ),
        )
    }

    /**
     * Handles version not found during document generation.
     * Returns 404 Not Found.
     */
    @ExceptionHandler(VersionNotFoundException::class)
    fun handleVersionNotFoundException(ex: VersionNotFoundException): ResponseEntity<ApiErrorResponse> {
        logger.warn("Version not found: {}", ex.message)

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiErrorResponse(
                code = "VERSION_NOT_FOUND",
                message = ex.message ?: "Version not found",
                details = mapOf(
                    "templateId" to ex.templateId.value,
                    "variantId" to ex.variantId.value,
                    "versionId" to ex.versionId.value,
                ),
            ),
        )
    }

    /**
     * Handles environment not found during document generation.
     * Returns 404 Not Found.
     */
    @ExceptionHandler(EnvironmentNotFoundException::class)
    fun handleEnvironmentNotFoundException(ex: EnvironmentNotFoundException): ResponseEntity<ApiErrorResponse> {
        logger.warn("Environment not found: {}", ex.message)

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiErrorResponse(
                code = "ENVIRONMENT_NOT_FOUND",
                message = ex.message ?: "Environment not found",
                details = mapOf(
                    "tenantId" to ex.tenantId.value,
                    "environmentId" to ex.environmentId.value,
                ),
            ),
        )
    }

    /**
     * Handles missing default variant during document generation.
     * Returns 404 Not Found.
     */
    @ExceptionHandler(DefaultVariantNotFoundException::class)
    fun handleDefaultVariantNotFoundException(ex: DefaultVariantNotFoundException): ResponseEntity<ApiErrorResponse> {
        logger.warn("Default variant not found: {}", ex.message)

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiErrorResponse(
                code = "DEFAULT_VARIANT_NOT_FOUND",
                message = ex.message ?: "No default variant found",
                details = mapOf(
                    "tenantId" to ex.tenantId.value,
                    "templateId" to ex.templateId.value,
                ),
            ),
        )
    }

    /**
     * Handles asset not found errors.
     * Returns 404 Not Found.
     */
    @ExceptionHandler(AssetNotFoundException::class)
    fun handleAssetNotFoundException(ex: AssetNotFoundException): ResponseEntity<ApiErrorResponse> {
        logger.warn("Asset not found: {}", ex.message)

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiErrorResponse(
                code = "ASSET_NOT_FOUND",
                message = ex.message ?: "Asset not found",
            ),
        )
    }

    /**
     * Handles asset too large errors.
     * Returns 413 Payload Too Large.
     */
    @ExceptionHandler(AssetTooLargeException::class)
    fun handleAssetTooLargeException(ex: AssetTooLargeException): ResponseEntity<ApiErrorResponse> {
        logger.warn("Asset too large: {}", ex.message)

        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(
            ApiErrorResponse(
                code = "ASSET_TOO_LARGE",
                message = ex.message ?: "Asset exceeds maximum size",
            ),
        )
    }

    /**
     * Handles unsupported asset type errors.
     * Returns 400 Bad Request.
     */
    @ExceptionHandler(UnsupportedAssetTypeException::class)
    fun handleUnsupportedAssetTypeException(ex: UnsupportedAssetTypeException): ResponseEntity<ApiErrorResponse> {
        logger.warn("Unsupported asset type: {}", ex.message)

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(
                code = "UNSUPPORTED_ASSET_TYPE",
                message = ex.message ?: "Unsupported asset media type",
            ),
        )
    }

    /**
     * Handles attempts to delete an asset that is referenced by template versions.
     * Returns 409 Conflict.
     */
    @ExceptionHandler(AssetInUseException::class)
    fun handleAssetInUseException(ex: AssetInUseException): ResponseEntity<ApiErrorResponse> {
        logger.warn("Cannot delete asset {}: in use by {} template(s)", ex.assetId, ex.usages.size)

        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiErrorResponse(
                code = "ASSET_IN_USE",
                message = ex.message ?: "Asset is in use and cannot be deleted",
                details = mapOf(
                    "assetId" to ex.assetId.value,
                    "usages" to ex.usages.map { mapOf("templateName" to it.templateName, "variantTitle" to it.variantTitle) },
                ),
            ),
        )
    }

    /**
     * Handles attempts to delete an environment that has active version activations.
     * Returns 409 Conflict.
     */
    @ExceptionHandler(EnvironmentInUseException::class)
    fun handleEnvironmentInUseException(ex: EnvironmentInUseException): ResponseEntity<ApiErrorResponse> {
        logger.warn("Cannot delete environment {}: {} active activation(s)", ex.environmentId, ex.activationCount)

        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiErrorResponse(
                code = "ENVIRONMENT_IN_USE",
                message = ex.message ?: "Environment is in use and cannot be deleted",
                details = mapOf(
                    "environmentId" to ex.environmentId.value,
                    "activationCount" to ex.activationCount,
                ),
            ),
        )
    }

    /**
     * Handles illegal argument errors (e.g., require() failures in commands/queries).
     * Returns 400 Bad Request.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(ex: IllegalArgumentException): ResponseEntity<ApiErrorResponse> {
        logger.warn("Bad request: {}", ex.message)

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(
                code = "BAD_REQUEST",
                message = ex.message ?: "Invalid request",
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
     * Handles tenant access denied errors.
     * Returns 403 Forbidden.
     */
    @ExceptionHandler(TenantAccessDeniedException::class)
    fun handleTenantAccessDeniedException(ex: TenantAccessDeniedException): ResponseEntity<ApiErrorResponse> {
        logger.warn("Tenant access denied: user={} tenant={}", ex.userEmail, ex.tenantId)

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ApiErrorResponse(
                code = "ACCESS_DENIED",
                message = "Access denied to tenant: ${ex.tenantId}",
            ),
        )
    }

    /**
     * Handles permission denied errors (fine-grained permissions).
     * Returns 403 Forbidden.
     */
    @ExceptionHandler(PermissionDeniedException::class)
    fun handlePermissionDeniedException(ex: PermissionDeniedException): ResponseEntity<ApiErrorResponse> {
        logger.warn("Permission denied: user={} tenant={} permission={}", ex.userEmail, ex.tenantId, ex.permission)

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ApiErrorResponse(
                code = "PERMISSION_DENIED",
                message = "Insufficient permissions",
            ),
        )
    }

    /**
     * Handles platform access denied errors (missing platform role).
     * Returns 403 Forbidden.
     */
    @ExceptionHandler(PlatformAccessDeniedException::class)
    fun handlePlatformAccessDeniedException(ex: PlatformAccessDeniedException): ResponseEntity<ApiErrorResponse> {
        logger.warn("Platform access denied: user={} requiredRole={}", ex.userEmail, ex.requiredRole)

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ApiErrorResponse(
                code = "PLATFORM_ACCESS_DENIED",
                message = "Platform role required: ${ex.requiredRole.name.lowercase().replace('_', '-')}",
            ),
        )
    }

    /**
     * Handles Spring Security access denied errors.
     * Returns 403 Forbidden.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException::class)
    fun handleAccessDeniedException(ex: org.springframework.security.access.AccessDeniedException): ResponseEntity<ApiErrorResponse> {
        logger.warn("Access denied: {}", ex.message)

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ApiErrorResponse(
                code = "ACCESS_DENIED",
                message = "Access denied",
            ),
        )
    }

    /**
     * Handles authentication errors.
     * Returns 401 Unauthorized.
     */
    @ExceptionHandler(org.springframework.security.core.AuthenticationException::class)
    fun handleAuthenticationException(ex: org.springframework.security.core.AuthenticationException): ResponseEntity<ApiErrorResponse> {
        logger.warn("Authentication failed: {}", ex.message)

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            ApiErrorResponse(
                code = "UNAUTHORIZED",
                message = "Authentication required",
            ),
        )
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

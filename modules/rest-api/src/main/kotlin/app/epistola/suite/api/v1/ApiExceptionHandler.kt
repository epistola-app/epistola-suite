package app.epistola.suite.api.v1

import app.epistola.suite.assets.AssetInUseException
import app.epistola.suite.assets.AssetNotFoundException
import app.epistola.suite.assets.AssetTooLargeException
import app.epistola.suite.assets.UnsupportedAssetTypeException
import app.epistola.suite.attributes.AttributeNotFoundException
import app.epistola.suite.attributes.codelists.CodeListNotFoundException
import app.epistola.suite.attributes.codelists.commands.CodeListInUseException
import app.epistola.suite.attributes.codelists.commands.CodeListNotRefreshableException
import app.epistola.suite.attributes.commands.AllowedValuesInUseException
import app.epistola.suite.attributes.commands.AttributeInUseException
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.CatalogNotUpgradeableException
import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.documents.DefaultVariantNotFoundException
import app.epistola.suite.documents.DocumentNotFoundException
import app.epistola.suite.documents.EnvironmentNotFoundException
import app.epistola.suite.documents.GenerationJobNotCancellableException
import app.epistola.suite.documents.GenerationJobNotFoundException
import app.epistola.suite.documents.NoPublishedVersionException
import app.epistola.suite.documents.TemplateVariantNotFoundException
import app.epistola.suite.documents.VersionNotFoundException
import app.epistola.suite.documents.commands.BatchValidationException
import app.epistola.suite.environments.EnvironmentInUseException
import app.epistola.suite.fonts.FontNotFoundException
import app.epistola.suite.security.PermissionDeniedException
import app.epistola.suite.security.PlatformAccessDeniedException
import app.epistola.suite.security.TenantAccessDeniedException
import app.epistola.suite.stencils.StencilNotFoundException
import app.epistola.suite.stencils.StencilVersionNotDraftException
import app.epistola.suite.stencils.StencilVersionNotFoundException
import app.epistola.suite.stencils.StencilVersionNotPublishedException
import app.epistola.suite.templates.ActivationNotFoundException
import app.epistola.suite.templates.DraftNotFoundException
import app.epistola.suite.templates.NoActiveVersionException
import app.epistola.suite.templates.TemplateNotFoundException
import app.epistola.suite.templates.VersionArchivedException
import app.epistola.suite.templates.VersionNotDraftException
import app.epistola.suite.templates.VersionNotPublishedException
import app.epistola.suite.templates.commands.variants.DefaultVariantDeletionException
import app.epistola.suite.templates.commands.versions.VersionStillActiveException
import app.epistola.suite.templates.services.AmbiguousVariantResolutionException
import app.epistola.suite.templates.services.NoMatchingVariantException
import app.epistola.suite.templates.validation.DataModelValidationException
import app.epistola.suite.tenants.TenantNotFoundException
import app.epistola.suite.themes.ThemeInUseException
import app.epistola.suite.themes.ThemeNotFoundException
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingPathVariableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * Global exception handler for REST API controllers.
 * Provides consistent error responses across all API endpoints.
 */
@RestControllerAdvice(basePackages = ["app.epistola.suite.api.v1"])
class ApiExceptionHandler {

    private val logger = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    @ExceptionHandler(BatchValidationException::class)
    fun handleBatchValidationException(ex: BatchValidationException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Batch validation failed: {}", ex.message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
            .body(ex.toProblemBody(request))
    }

    /**
     * Handles theme not found errors.
     * Returns 404 Not Found.
     */
    @ExceptionHandler(ThemeNotFoundException::class)
    fun handleThemeNotFoundException(ex: ThemeNotFoundException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Theme not found: {}", ex.themeId)

        return problemResponse(request, ApiProblemTypes.THEME_NOT_FOUND, ex.message ?: "Theme not found", mapOf("themeId" to ex.themeId.value))
    }

    @ExceptionHandler(TenantNotFoundException::class)
    fun handleTenantNotFoundException(ex: TenantNotFoundException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Tenant not found: {}", ex.tenantId)

        return problemResponse(request, ApiProblemTypes.TENANT_NOT_FOUND, ex.message ?: "Tenant not found", mapOf("tenantId" to ex.tenantId.value))
    }

    @ExceptionHandler(AttributeNotFoundException::class)
    fun handleAttributeNotFoundException(ex: AttributeNotFoundException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Attribute not found: tenant={} catalog={} attribute={}", ex.tenantId, ex.catalogId, ex.attributeId)

        return problemResponse(
            request,
            ApiProblemTypes.ATTRIBUTE_NOT_FOUND,
            ex.message ?: "Attribute not found",
            mapOf(
                "tenantId" to ex.tenantId.value,
                "catalogId" to ex.catalogId.value,
                "attributeId" to ex.attributeId.value,
            ),
        )
    }

    @ExceptionHandler(CodeListNotFoundException::class)
    fun handleCodeListNotFoundException(ex: CodeListNotFoundException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Code list not found: tenant={} catalog={} codeList={}", ex.tenantId, ex.catalogId, ex.codeListId)

        return problemResponse(
            request,
            ApiProblemTypes.CODE_LIST_NOT_FOUND,
            ex.message ?: "Code list not found",
            mapOf(
                "tenantId" to ex.tenantId.value,
                "catalogId" to ex.catalogId.value,
                "codeListId" to ex.codeListId.value,
            ),
        )
    }

    @ExceptionHandler(TemplateNotFoundException::class)
    fun handleTemplateNotFoundException(ex: TemplateNotFoundException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Template not found: tenant={} template={}", ex.tenantId, ex.templateId)

        return problemResponse(
            request,
            ApiProblemTypes.TEMPLATE_NOT_FOUND,
            ex.message ?: "Template not found",
            mapOf(
                "tenantId" to ex.tenantId.value,
                "templateId" to ex.templateId.value,
            ),
        )
    }

    @ExceptionHandler(StencilNotFoundException::class)
    fun handleStencilNotFoundException(ex: StencilNotFoundException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Stencil not found: tenant={} stencil={}", ex.tenantId, ex.stencilId)

        return problemResponse(
            request,
            ApiProblemTypes.STENCIL_NOT_FOUND,
            ex.message ?: "Stencil not found",
            mapOf(
                "tenantId" to ex.tenantId.value,
                "stencilId" to ex.stencilId.value,
            ),
        )
    }

    @ExceptionHandler(StencilVersionNotFoundException::class)
    fun handleStencilVersionNotFoundException(ex: StencilVersionNotFoundException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Stencil version not found: tenant={} catalog={} stencil={} version={}", ex.tenantId, ex.catalogId, ex.stencilId, ex.versionId)

        return problemResponse(
            request,
            ApiProblemTypes.STENCIL_VERSION_NOT_FOUND,
            ex.message ?: "Stencil version not found",
            mapOf(
                "tenantId" to ex.tenantId.value,
                "catalogId" to ex.catalogId.value,
                "stencilId" to ex.stencilId.value,
                "versionId" to ex.versionId.value,
            ),
        )
    }

    @ExceptionHandler(StencilVersionNotDraftException::class)
    fun handleStencilVersionNotDraftException(ex: StencilVersionNotDraftException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Stencil version not draft: tenant={} catalog={} stencil={} version={}", ex.tenantId, ex.catalogId, ex.stencilId, ex.versionId)

        return problemResponse(
            request,
            ApiProblemTypes.STENCIL_VERSION_NOT_DRAFT,
            ex.message ?: "Stencil version is not a draft",
            mapOf(
                "tenantId" to ex.tenantId.value,
                "catalogId" to ex.catalogId.value,
                "stencilId" to ex.stencilId.value,
                "versionId" to ex.versionId.value,
            ),
        )
    }

    @ExceptionHandler(StencilVersionNotPublishedException::class)
    fun handleStencilVersionNotPublishedException(ex: StencilVersionNotPublishedException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Stencil version not published: tenant={} catalog={} stencil={} version={}", ex.tenantId, ex.catalogId, ex.stencilId, ex.versionId)

        return problemResponse(
            request,
            ApiProblemTypes.STENCIL_VERSION_NOT_PUBLISHED,
            ex.message ?: "Stencil version is not published",
            mapOf(
                "tenantId" to ex.tenantId.value,
                "catalogId" to ex.catalogId.value,
                "stencilId" to ex.stencilId.value,
                "versionId" to ex.versionId.value,
            ),
        )
    }

    @ExceptionHandler(FontNotFoundException::class)
    fun handleFontNotFoundException(ex: FontNotFoundException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Font not found: tenant={} catalog={} font={}", ex.tenantId, ex.catalogId, ex.fontId)

        return problemResponse(
            request,
            ApiProblemTypes.FONT_NOT_FOUND,
            ex.message ?: "Font not found",
            mapOf(
                "tenantId" to ex.tenantId.value,
                "catalogId" to ex.catalogId.value,
                "fontId" to ex.fontId.value,
            ),
        )
    }

    @ExceptionHandler(GenerationJobNotFoundException::class)
    fun handleGenerationJobNotFoundException(ex: GenerationJobNotFoundException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Generation job not found: tenant={} requestId={}", ex.tenantId, ex.requestId)

        return problemResponse(
            request,
            ApiProblemTypes.GENERATION_JOB_NOT_FOUND,
            ex.message ?: "Generation job not found",
            mapOf(
                "tenantId" to ex.tenantId.value,
                "requestId" to ex.requestId.value.toString(),
            ),
        )
    }

    @ExceptionHandler(GenerationJobNotCancellableException::class)
    fun handleGenerationJobNotCancellableException(ex: GenerationJobNotCancellableException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Generation job not cancellable: tenant={} requestId={} reason={}", ex.tenantId, ex.requestId, ex.reason)

        return problemResponse(
            request,
            ApiProblemTypes.GENERATION_JOB_NOT_CANCELLABLE,
            ex.message ?: "Generation job cannot be cancelled",
            mapOf(
                "tenantId" to ex.tenantId.value,
                "requestId" to ex.requestId.value.toString(),
            ),
        )
    }

    @ExceptionHandler(DocumentNotFoundException::class)
    fun handleDocumentNotFoundException(ex: DocumentNotFoundException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Document not found: tenant={} documentId={}", ex.tenantId, ex.documentId)

        return problemResponse(
            request,
            ApiProblemTypes.DOCUMENT_NOT_FOUND,
            ex.message ?: "Document not found",
            mapOf(
                "tenantId" to ex.tenantId.value,
                "documentId" to ex.documentId.value.toString(),
            ),
        )
    }

    @ExceptionHandler(DraftNotFoundException::class)
    fun handleDraftNotFoundException(ex: DraftNotFoundException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Draft not found: tenant={} variant={}", ex.tenantId, ex.variantId)

        return problemResponse(
            request,
            ApiProblemTypes.DRAFT_NOT_FOUND,
            ex.message ?: "Draft not found",
            mapOf(
                "tenantId" to ex.tenantId.value,
                "variantId" to ex.variantId.value,
            ),
        )
    }

    @ExceptionHandler(VersionNotDraftException::class)
    fun handleVersionNotDraftException(ex: VersionNotDraftException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Version not draft: tenant={} version={}", ex.tenantId, ex.versionId)

        return problemResponse(
            request,
            ApiProblemTypes.VERSION_NOT_DRAFT,
            ex.message ?: "Version is not a draft",
            mapOf(
                "tenantId" to ex.tenantId.value,
                "versionId" to ex.versionId.value,
            ),
        )
    }

    @ExceptionHandler(VersionNotPublishedException::class)
    fun handleVersionNotPublishedException(ex: VersionNotPublishedException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Version not published: tenant={} version={}", ex.tenantId, ex.versionId)

        return problemResponse(
            request,
            ApiProblemTypes.VERSION_NOT_PUBLISHED,
            ex.message ?: "Version is not published",
            mapOf(
                "tenantId" to ex.tenantId.value,
                "versionId" to ex.versionId.value,
            ),
        )
    }

    @ExceptionHandler(VersionArchivedException::class)
    fun handleVersionArchivedException(ex: VersionArchivedException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Version archived: tenant={} version={}", ex.tenantId, ex.versionId)

        return problemResponse(
            request,
            ApiProblemTypes.VERSION_ARCHIVED,
            ex.message ?: "Version is archived",
            mapOf(
                "tenantId" to ex.tenantId.value,
                "versionId" to ex.versionId.value,
            ),
        )
    }

    @ExceptionHandler(ActivationNotFoundException::class)
    fun handleActivationNotFoundException(ex: ActivationNotFoundException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Activation not found: tenant={} variant={} environment={}", ex.tenantId, ex.variantId, ex.environmentId)

        return problemResponse(
            request,
            ApiProblemTypes.ACTIVATION_NOT_FOUND,
            ex.message ?: "Activation not found",
            mapOf(
                "tenantId" to ex.tenantId.value,
                "variantId" to ex.variantId.value,
                "environmentId" to ex.environmentId.value,
            ),
        )
    }

    @ExceptionHandler(NoActiveVersionException::class)
    fun handleNoActiveVersionException(ex: NoActiveVersionException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("No active version: tenant={} variant={} environment={}", ex.tenantId, ex.variantId, ex.environmentId)

        return problemResponse(
            request,
            ApiProblemTypes.NO_ACTIVE_VERSION,
            ex.message ?: "No active version found",
            mapOf(
                "tenantId" to ex.tenantId.value,
                "variantId" to ex.variantId.value,
                "environmentId" to ex.environmentId.value,
            ),
        )
    }

    /**
     * Handles attempts to delete a theme that is in use.
     * Returns 409 Conflict.
     */
    @ExceptionHandler(ThemeInUseException::class)
    fun handleThemeInUseException(ex: ThemeInUseException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Theme in use, cannot delete: {}", ex.themeId)

        return problemResponse(request, ApiProblemTypes.THEME_IN_USE, ex.message ?: "Theme is in use and cannot be deleted", mapOf("themeId" to ex.themeId.value))
    }

    /**
     * Handles attempts to delete the default variant.
     * Returns 409 Conflict.
     */
    @ExceptionHandler(DefaultVariantDeletionException::class)
    fun handleDefaultVariantDeletionException(ex: DefaultVariantDeletionException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Cannot delete default variant: {}", ex.variantId)

        return problemResponse(request, ApiProblemTypes.DEFAULT_VARIANT_DELETION, ex.message ?: "Cannot delete the default variant", mapOf("variantId" to ex.variantId.value))
    }

    /**
     * Handles attempts to archive a version that is still active in environments.
     * Returns 409 Conflict.
     */
    @ExceptionHandler(VersionStillActiveException::class)
    fun handleVersionStillActiveException(ex: VersionStillActiveException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Cannot archive version {}: still active in environments", ex.versionId)

        return problemResponse(
            request,
            ApiProblemTypes.VERSION_STILL_ACTIVE,
            ex.message ?: "Version is still active in one or more environments",
            mapOf(
                "versionId" to ex.versionId.value,
                "variantId" to ex.variantId.value,
                "activeEnvironments" to ex.activeEnvironments.map { it.value },
            ),
        )
    }

    /**
     * Handles data model validation errors (schema validation failures).
     * Returns 422 Unprocessable Entity.
     */
    @ExceptionHandler(DataModelValidationException::class)
    fun handleDataModelValidationException(ex: DataModelValidationException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Data model validation failed: {} examples with errors", ex.validationErrors.size)

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
            .body(ex.toProblemBody(request))
    }

    /**
     * Handles variant resolution failures when no matching variant is found.
     * Returns 404 Not Found.
     */
    @ExceptionHandler(NoMatchingVariantException::class)
    fun handleNoMatchingVariantException(ex: NoMatchingVariantException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("No matching variant for template {}: {}", ex.templateId, ex.criteria)

        return problemResponse(
            request,
            ApiProblemTypes.NO_MATCHING_VARIANT,
            ex.message ?: "No matching variant found",
            mapOf(
                "templateId" to ex.templateId.value,
                "requiredAttributes" to ex.criteria.requiredAttributes,
            ),
        )
    }

    /**
     * Handles ambiguous variant resolution when multiple variants tie on score.
     * Returns 409 Conflict.
     */
    @ExceptionHandler(AmbiguousVariantResolutionException::class)
    fun handleAmbiguousVariantResolutionException(ex: AmbiguousVariantResolutionException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Ambiguous variant resolution for template {}: {}", ex.templateId, ex.tiedVariantIds)

        return problemResponse(
            request,
            ApiProblemTypes.AMBIGUOUS_VARIANT,
            ex.message ?: "Ambiguous variant resolution",
            mapOf(
                "templateId" to ex.templateId.value,
                "tiedVariants" to ex.tiedVariantIds.map { it.value },
            ),
        )
    }

    /**
     * Handles attempts to delete an attribute that is still referenced by variants.
     * Returns 409 Conflict.
     */
    @ExceptionHandler(AttributeInUseException::class)
    fun handleAttributeInUseException(ex: AttributeInUseException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Cannot delete attribute {}: still in use by {} variant(s)", ex.attributeId, ex.variantCount)

        return problemResponse(
            request,
            ApiProblemTypes.ATTRIBUTE_IN_USE,
            ex.message ?: "Attribute is in use and cannot be deleted",
            mapOf(
                "attributeId" to ex.attributeId.key,
                "variantCount" to ex.variantCount,
            ),
        )
    }

    /**
     * Handles attempts to narrow allowed values when existing variants use the removed values.
     * Returns 409 Conflict.
     */
    @ExceptionHandler(AllowedValuesInUseException::class)
    fun handleAllowedValuesInUseException(ex: AllowedValuesInUseException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Cannot narrow allowed values for attribute {}: values {} are in use", ex.attributeId, ex.removedValues)

        return problemResponse(
            request,
            ApiProblemTypes.ALLOWED_VALUES_IN_USE,
            ex.message ?: "Cannot remove allowed values that are in use by existing variants",
            mapOf(
                "attributeId" to ex.attributeId.key,
                "valuesInUse" to ex.removedValues,
            ),
        )
    }

    /**
     * Handles template/variant not found during document generation.
     * Returns 404 Not Found.
     */
    @ExceptionHandler(TemplateVariantNotFoundException::class)
    fun handleTemplateVariantNotFoundException(ex: TemplateVariantNotFoundException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Template variant not found: {}", ex.message)

        return problemResponse(
            request,
            ApiProblemTypes.TEMPLATE_VARIANT_NOT_FOUND,
            ex.message ?: "Template variant not found",
            mapOf(
                "tenantId" to ex.tenantId.value,
                "templateId" to ex.templateId.value,
                "variantId" to ex.variantId.value,
            ),
        )
    }

    /**
     * Handles version not found during document generation.
     * Returns 404 Not Found.
     */
    @ExceptionHandler(VersionNotFoundException::class)
    fun handleVersionNotFoundException(ex: VersionNotFoundException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Version not found: {}", ex.message)

        return problemResponse(
            request,
            ApiProblemTypes.VERSION_NOT_FOUND,
            ex.message ?: "Version not found",
            mapOf(
                "templateId" to ex.templateId.value,
                "variantId" to ex.variantId.value,
                "versionId" to ex.versionId.value,
            ),
        )
    }

    /**
     * Handles environment not found during document generation.
     * Returns 404 Not Found.
     */
    @ExceptionHandler(EnvironmentNotFoundException::class)
    fun handleEnvironmentNotFoundException(ex: EnvironmentNotFoundException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Environment not found: {}", ex.message)

        return problemResponse(
            request,
            ApiProblemTypes.ENVIRONMENT_NOT_FOUND,
            ex.message ?: "Environment not found",
            mapOf(
                "tenantId" to ex.tenantId.value,
                "environmentId" to ex.environmentId.value,
            ),
        )
    }

    /**
     * Handles missing published version during document generation.
     * Returns 404 Not Found.
     */
    @ExceptionHandler(NoPublishedVersionException::class)
    fun handleNoPublishedVersionException(ex: NoPublishedVersionException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("No published version: {}", ex.message)

        return problemResponse(
            request,
            ApiProblemTypes.NO_PUBLISHED_VERSION,
            ex.message ?: "No published version found",
            mapOf(
                "tenantId" to ex.tenantId.value,
                "templateId" to ex.templateId.value,
                "variantId" to ex.variantId.value,
            ),
        )
    }

    /**
     * Handles missing default variant during document generation.
     * Returns 404 Not Found.
     */
    @ExceptionHandler(DefaultVariantNotFoundException::class)
    fun handleDefaultVariantNotFoundException(ex: DefaultVariantNotFoundException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Default variant not found: {}", ex.message)

        return problemResponse(
            request,
            ApiProblemTypes.DEFAULT_VARIANT_NOT_FOUND,
            ex.message ?: "No default variant found",
            mapOf(
                "tenantId" to ex.tenantId.value,
                "templateId" to ex.templateId.value,
            ),
        )
    }

    /**
     * Handles asset not found errors.
     * Returns 404 Not Found.
     */
    @ExceptionHandler(AssetNotFoundException::class)
    fun handleAssetNotFoundException(ex: AssetNotFoundException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Asset not found: {}", ex.message)

        return problemResponse(request, ApiProblemTypes.ASSET_NOT_FOUND, ex.message ?: "Asset not found")
    }

    /**
     * Handles asset too large errors.
     * Returns 413 Payload Too Large.
     */
    @ExceptionHandler(AssetTooLargeException::class)
    fun handleAssetTooLargeException(ex: AssetTooLargeException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Asset too large: {}", ex.message)

        return problemResponse(request, ApiProblemTypes.ASSET_TOO_LARGE, ex.message ?: "Asset exceeds maximum size")
    }

    /**
     * Handles unsupported asset type errors.
     * Returns 400 Bad Request.
     */
    @ExceptionHandler(UnsupportedAssetTypeException::class)
    fun handleUnsupportedAssetTypeException(ex: UnsupportedAssetTypeException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Unsupported asset type: {}", ex.message)

        return problemResponse(request, ApiProblemTypes.UNSUPPORTED_ASSET_TYPE, ex.message ?: "Unsupported asset media type")
    }

    /**
     * Handles attempts to delete an asset that is referenced by template versions.
     * Returns 409 Conflict.
     */
    @ExceptionHandler(AssetInUseException::class)
    fun handleAssetInUseException(ex: AssetInUseException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Cannot delete asset {}: in use by {} template(s)", ex.assetId, ex.usages.size)

        return problemResponse(
            request,
            ApiProblemTypes.ASSET_IN_USE,
            ex.message ?: "Asset is in use and cannot be deleted",
            mapOf(
                "assetId" to ex.assetId.value,
                "usages" to ex.usages.map { mapOf("templateName" to it.templateName, "variantTitle" to it.variantTitle) },
            ),
        )
    }

    /**
     * Handles attempts to delete an environment that has active version activations.
     * Returns 409 Conflict.
     */
    @ExceptionHandler(EnvironmentInUseException::class)
    fun handleEnvironmentInUseException(ex: EnvironmentInUseException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Cannot delete environment {}: {} active activation(s)", ex.environmentId, ex.activationCount)

        return problemResponse(
            request,
            ApiProblemTypes.ENVIRONMENT_IN_USE,
            ex.message ?: "Environment is in use and cannot be deleted",
            mapOf(
                "environmentId" to ex.environmentId.value,
                "activationCount" to ex.activationCount,
            ),
        )
    }

    /**
     * Handles illegal argument errors (e.g., require() failures in commands/queries).
     * Returns 400 Bad Request.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(ex: IllegalArgumentException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Bad request: {}", ex.message)

        return problemResponse(request, ApiProblemTypes.BAD_REQUEST, ex.message ?: "Invalid request")
    }

    /**
     * Handles general validation errors (e.g., invalid field values).
     * Returns 400 Bad Request.
     */
    @ExceptionHandler(ValidationException::class)
    fun handleValidationException(ex: ValidationException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Validation failed [{}] for field '{}': {}", ex.code.wire, ex.field, ex.message)

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
            .body(ex.toValidationProblemBody(request))
    }

    /**
     * Handles tenant access denied errors.
     * Returns 403 Forbidden.
     */
    @ExceptionHandler(TenantAccessDeniedException::class)
    fun handleTenantAccessDeniedException(ex: TenantAccessDeniedException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Tenant access denied: user={} tenant={}", ex.userEmail, ex.tenantId)

        return problemResponse(request, ApiProblemTypes.ACCESS_DENIED, "Access denied to tenant: ${ex.tenantId}")
    }

    /**
     * Handles permission denied errors (fine-grained permissions).
     * Returns 403 Forbidden.
     */
    @ExceptionHandler(PermissionDeniedException::class)
    fun handlePermissionDeniedException(ex: PermissionDeniedException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Permission denied: user={} tenant={} permission={}", ex.userEmail, ex.tenantId, ex.permission)

        return problemResponse(request, ApiProblemTypes.PERMISSION_DENIED, "Insufficient permissions")
    }

    /**
     * Handles platform access denied errors (missing platform role).
     * Returns 403 Forbidden.
     */
    @ExceptionHandler(PlatformAccessDeniedException::class)
    fun handlePlatformAccessDeniedException(ex: PlatformAccessDeniedException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Platform access denied: user={} requiredRole={}", ex.userEmail, ex.requiredRole)

        return problemResponse(request, ApiProblemTypes.PLATFORM_ACCESS_DENIED, "Platform role required: ${ex.requiredRole.name.lowercase().replace('_', '-')}")
    }

    /**
     * Handles Spring Security access denied errors.
     * Returns 403 Forbidden.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException::class)
    fun handleAccessDeniedException(ex: org.springframework.security.access.AccessDeniedException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Access denied: {}", ex.message)

        return problemResponse(request, ApiProblemTypes.ACCESS_DENIED, "Access denied")
    }

    /**
     * Handles authentication errors.
     * Returns 401 Unauthorized.
     */
    @ExceptionHandler(org.springframework.security.core.AuthenticationException::class)
    fun handleAuthenticationException(ex: org.springframework.security.core.AuthenticationException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Authentication failed: {}", ex.message)

        return problemResponse(request, ApiProblemTypes.UNAUTHORIZED, "Authentication required")
    }

    /**
     * Handles API operations that exist in the contract but have not been
     * implemented by this server yet. Returns 501 Not Implemented.
     */
    @ExceptionHandler(ApiOperationNotImplementedException::class)
    fun handleApiOperationNotImplementedException(ex: ApiOperationNotImplementedException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("API operation not implemented: {}", ex.operation)

        return problemResponse(
            request,
            ApiProblemTypes.OPERATION_NOT_IMPLEMENTED,
            ex.message ?: "API operation is not implemented",
            mapOf("operation" to ex.operation),
        )
    }

    /**
     * Handles writes to SUBSCRIBED catalogs (e.g. the bundled `system`
     * catalog). Returns 409 Conflict — the resource exists but the catalog
     * itself is read-only at the REST API surface.
     */
    @ExceptionHandler(CatalogReadOnlyException::class)
    fun handleCatalogReadOnlyException(ex: CatalogReadOnlyException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Write to read-only catalog rejected: {}", ex.message)

        return problemResponse(request, ApiProblemTypes.CATALOG_READ_ONLY, ex.message ?: "Catalog is subscribed and cannot be modified through this API")
    }

    /**
     * Handles a lookup for a catalog that does not exist (e.g. upgrade
     * preview for an unknown slug). Returns 404 Not Found — was a misleading
     * 400 via the generic `IllegalArgumentException` handler.
     */
    @ExceptionHandler(CatalogNotFoundException::class)
    fun handleCatalogNotFoundException(ex: CatalogNotFoundException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Catalog not found: {}", ex.catalogKey)

        return problemResponse(request, ApiProblemTypes.CATALOG_NOT_FOUND, ex.message ?: "Catalog not found", mapOf("catalogId" to ex.catalogKey.value))
    }

    /**
     * Handles an upgrade/upgrade-preview requested for a catalog that cannot
     * be upgraded (not subscribed / no per-resource baseline). Returns 409
     * Conflict — wrong catalog state for the operation, not client input;
     * was a generic 500.
     */
    @ExceptionHandler(CatalogNotUpgradeableException::class)
    fun handleCatalogNotUpgradeableException(ex: CatalogNotUpgradeableException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Catalog not upgradeable: {}", ex.message)

        return problemResponse(request, ApiProblemTypes.CATALOG_NOT_UPGRADEABLE, ex.message ?: "Catalog cannot be upgraded", mapOf("catalogId" to ex.catalogKey.value))
    }

    /**
     * Handles attempts to delete a code list that is still bound by an
     * attribute. Returns 409 Conflict.
     */
    @ExceptionHandler(CodeListInUseException::class)
    fun handleCodeListInUseException(ex: CodeListInUseException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Cannot delete code list: still bound by an attribute. {}", ex.message)

        return problemResponse(request, ApiProblemTypes.CODE_LIST_IN_USE, ex.message ?: "Code list is in use and cannot be deleted")
    }

    /**
     * Handles refresh attempts on non-URL-sourced code lists. Returns 400.
     */
    @ExceptionHandler(CodeListNotRefreshableException::class)
    fun handleCodeListNotRefreshableException(ex: CodeListNotRefreshableException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Code list refresh rejected: {}", ex.message)

        return problemResponse(request, ApiProblemTypes.CODE_LIST_NOT_REFRESHABLE, ex.message ?: "Code list is not URL-sourced and cannot be refreshed")
    }

    /**
     * Handles Spring `@Valid` validation failures on controller method parameters.
     * Returns 400 Bad Request.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValidException(ex: MethodArgumentNotValidException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Validation failed on request body: {} errors", ex.bindingResult.errorCount)

        val fieldErrors = ex.bindingResult.fieldErrors.map {
            app.epistola.api.model.FieldError(
                field = it.field,
                message = it.defaultMessage ?: "Invalid value",
                rejectedValue = it.rejectedValue?.toString(),
            )
        }
        val globalErrors = ex.bindingResult.globalErrors.map {
            app.epistola.api.model.FieldError(
                field = it.objectName,
                message = it.defaultMessage ?: "Invalid value",
                rejectedValue = null,
            )
        }
        return problemResponse(
            request,
            ApiProblemTypes.validationProblemType(ValidationCode.GENERIC),
            "Request body validation failed",
            mapOf("errors" to fieldErrors + globalErrors),
        )
    }

    /**
     * Handles unsupported HTTP methods (e.g., POST to a GET-only endpoint).
     * Returns 405 Method Not Allowed.
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupportedException(ex: org.springframework.web.HttpRequestMethodNotSupportedException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Method not allowed: {} on {}", ex.method, request.requestURI)
        val supportedMethods = ex.supportedMethods?.toList() ?: emptyList()

        val response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        if (supportedMethods.isNotEmpty()) {
            response.header(HttpHeaders.ALLOW, supportedMethods.joinToString(", "))
        }
        return response.body(
            problemBody(
                request,
                ApiProblemTypes.METHOD_NOT_ALLOWED,
                "HTTP method ${ex.method} is not supported for this resource",
                mapOf("method" to ex.method, "supportedMethods" to supportedMethods),
            ),
        )
    }

    /**
     * Handles unsupported media types in request bodies.
     * Returns 415 Unsupported Media Type.
     */
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException::class)
    fun handleMediaTypeNotSupportedException(ex: org.springframework.web.HttpMediaTypeNotSupportedException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Unsupported media type: {}", ex.contentType)

        return problemResponse(
            request,
            ApiProblemTypes.UNSUPPORTED_MEDIA_TYPE,
            "Media type ${ex.contentType} is not supported",
            mapOf("contentType" to ex.contentType.toString(), "supportedTypes" to ex.supportedMediaTypes.map { it.toString() }),
        )
    }

    /**
     * Handles cases where the Accept header requests a representation we cannot produce.
     * Returns 406 Not Acceptable.
     */
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotAcceptableException::class)
    fun handleMediaTypeNotAcceptableException(ex: org.springframework.web.HttpMediaTypeNotAcceptableException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Not acceptable: Accept={}", request.getHeader("Accept"))

        return problemResponse(
            request,
            ApiProblemTypes.NOT_ACCEPTABLE,
            "The requested representation is not available",
            mapOf("acceptHeader" to (request.getHeader("Accept") ?: ""), "supportedTypes" to ex.supportedMediaTypes.map { it.toString() }),
        )
    }

    /**
     * Handles unreadable request bodies (e.g., malformed JSON).
     * Returns 400 Bad Request.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMessageNotReadableException(ex: HttpMessageNotReadableException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Unreadable request body: {}", ex.message)

        return problemResponse(request, ApiProblemTypes.BAD_REQUEST, "Request body is malformed or unreadable")
    }

    /**
     * Handles missing required query / form parameters.
     * Returns 400 Bad Request.
     */
    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingServletRequestParameterException(ex: MissingServletRequestParameterException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Missing parameter: {} ({})", ex.parameterName, ex.parameterType)

        return problemResponse(
            request,
            ApiProblemTypes.MISSING_PARAMETER,
            "Required parameter '${ex.parameterName}' is missing",
            mapOf("parameterName" to ex.parameterName, "parameterType" to ex.parameterType),
        )
    }

    /**
     * Handles missing required path variables.
     * Returns 400 Bad Request.
     */
    @ExceptionHandler(MissingPathVariableException::class)
    fun handleMissingPathVariableException(ex: MissingPathVariableException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Missing path variable: {}", ex.variableName)

        return problemResponse(
            request,
            ApiProblemTypes.MISSING_PATH_VARIABLE,
            "Required path variable '${ex.variableName}' is missing",
            mapOf("variableName" to ex.variableName),
        )
    }

    /**
     * Handles type mismatches in path / query parameters (e.g., string where UUID expected).
     * Returns 400 Bad Request.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatchException(ex: MethodArgumentTypeMismatchException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Type mismatch for parameter '{}': expected {}, got {}", ex.name, ex.requiredType?.simpleName, ex.value)

        return problemResponse(
            request,
            ApiProblemTypes.TYPE_MISMATCH,
            "Parameter '${ex.name}' could not be converted to the expected type",
            mapOf("parameterName" to ex.name, "expectedType" to (ex.requiredType?.simpleName ?: ""), "actualValue" to (ex.value?.toString() ?: "")),
        )
    }

    /**
     * Handles requests to non-existent endpoints (no handler mapped).
     * Returns 404 Not Found.
     */
    @ExceptionHandler(NoHandlerFoundException::class)
    fun handleNoHandlerFoundException(ex: NoHandlerFoundException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("No handler found for {} {}", ex.httpMethod, ex.requestURL)

        return problemResponse(
            request,
            ApiProblemTypes.NOT_FOUND,
            "No endpoint exists for ${ex.httpMethod} ${ex.requestURL}",
            mapOf("path" to ex.requestURL),
        )
    }

    /**
     * Handles requests to non-existent static resources.
     * Returns 404 Not Found.
     */
    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFoundException(ex: NoResourceFoundException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.warn("Resource not found: {}", ex.resourcePath)

        return problemResponse(
            request,
            ApiProblemTypes.NOT_FOUND,
            "Resource not found: ${ex.resourcePath}",
            mapOf("path" to ex.resourcePath),
        )
    }

    /**
     * Handles unexpected exceptions.
     * Returns 500 Internal Server Error.
     */
    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        logger.error("Unexpected error in API controller", ex)

        return problemResponse(request, ApiProblemTypes.INTERNAL_ERROR, "An unexpected error occurred")
    }
}

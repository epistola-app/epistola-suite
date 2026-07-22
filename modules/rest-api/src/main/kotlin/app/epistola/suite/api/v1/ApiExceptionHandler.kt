package app.epistola.suite.api.v1

import app.epistola.api.model.ValidationError
import app.epistola.suite.api.v1.shared.UnsupportedSortDirectionException
import app.epistola.suite.api.v1.shared.UnsupportedSortException
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
import app.epistola.suite.catalog.migrations.CatalogSchemaTooNewException
import app.epistola.suite.catalog.migrations.CatalogSchemaTooOldException
import app.epistola.suite.catalog.migrations.CatalogSchemaUnknownException
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
import app.epistola.suite.templates.contracts.ContractPublishConflictException
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
import org.springframework.beans.TypeMismatchException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import tools.jackson.databind.DatabindException
import java.sql.SQLException

/**
 * Global exception handler for REST API controllers.
 *
 * Provides consistent RFC 9457 problem-detail responses for both framework
 * exceptions (405/415/406/404/400) and domain exceptions. Uses
 * [problemDetail] as the centralized builder so that every error response
 * carries the correct type URI, title, status, instance, and structured
 * code extension.
 *
 * Framework exceptions are handled by [ResponseEntityExceptionHandler] and
 * enriched in [handleExceptionInternal], avoiding duplicated handlers.
 *
 * Simple domain exceptions are dispatched through [handleMappedApiException]
 * via the [ApiExceptionMappings] registry, removing the need for one
 * dedicated method per exception class.
 */
@RestControllerAdvice(basePackages = ["app.epistola.suite.api.v1"])
class ApiExceptionHandler : ResponseEntityExceptionHandler() {

    private val logger = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    override fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any> {
        // A required non-null DTO property that is absent or explicitly null fails during
        // Jackson binding, before any @Valid check or command validation runs. Without this,
        // omitting a required field yields a bare "malformed or unreadable" that never names
        // it, while sending it blank yields a field-scoped error — the same mistake reported
        // two different ways. Surface the bound property so both paths name the field.
        val field = ex.bindingFieldName()
        if (field != null) {
            logger.warn("Request body rejected: property '{}' is missing or not bindable", field)
            return problemEntity(
                request,
                headers,
                ApiProblemTypes.validationProblemType(ValidationCode.GENERIC),
                "Request body validation failed",
                mapOf(
                    "errors" to listOf(
                        // Deliberately covers both shapes a binding failure takes — an absent
                        // required property and a value of the wrong type — because they are not
                        // reliably distinguishable here, and claiming "required" for a type
                        // mismatch would misdirect the client.
                        ValidationError(field = field, message = "$field is missing or invalid", rejectedValue = null),
                    ),
                ),
            )
        }

        logger.warn("Unreadable request body: {}", ex.message)

        return problemEntity(request, headers, ApiProblemTypes.BAD_REQUEST, "Request body is malformed or unreadable")
    }

    /**
     * The JSON property a binding failure is anchored to, or null when the body failed as a
     * whole (malformed JSON, wrong root type) and no single field is to blame.
     */
    private fun HttpMessageNotReadableException.bindingFieldName(): String? = (cause as? DatabindException)
        ?.path
        ?.firstOrNull { !it.propertyName.isNullOrBlank() }
        ?.propertyName

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any> {
        logger.warn("Validation failed on request body: {} errors", ex.bindingResult.errorCount)

        val fieldErrors = ex.bindingResult.fieldErrors.map {
            ValidationError(
                field = it.field,
                message = it.defaultMessage ?: "Invalid value",
                rejectedValue = it.rejectedValue?.toString(),
            )
        }
        val globalErrors = ex.bindingResult.globalErrors.map {
            ValidationError(
                field = it.objectName,
                message = it.defaultMessage ?: "Invalid value",
                rejectedValue = null,
            )
        }
        return problemEntity(
            request,
            headers,
            ApiProblemTypes.validationProblemType(ValidationCode.GENERIC),
            "Request body validation failed",
            mapOf("errors" to fieldErrors + globalErrors),
        )
    }

    override fun handleTypeMismatch(
        ex: TypeMismatchException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any> {
        val typeMismatch = ex as? MethodArgumentTypeMismatchException
            ?: return super.handleTypeMismatch(ex, headers, status, request)!!
        logger.warn("Type mismatch for parameter '{}': expected {}, got {}", typeMismatch.name, typeMismatch.requiredType?.simpleName, typeMismatch.value)

        return problemEntity(
            request,
            headers,
            ApiProblemTypes.TYPE_MISMATCH,
            "Parameter '${typeMismatch.name}' could not be converted to the expected type",
            mapOf(
                "parameterName" to typeMismatch.name,
                "expectedType" to (typeMismatch.requiredType?.simpleName ?: ""),
                "actualValue" to (typeMismatch.value?.toString() ?: ""),
            ),
        )
    }

    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val problemBody = body as? ProblemDetail
            ?: ProblemDetail.forStatusAndDetail(statusCode, ex.message ?: "Request failed")
        val type = ApiProblemTypes.forStatus(statusCode)
        problemBody.type = type.type
        problemBody.title = type.title
        (request as? ServletWebRequest)?.request?.let { servletRequest ->
            problemBody.instance = servletRequest.problemInstance()
        }
        headers.contentType = MediaType.APPLICATION_PROBLEM_JSON

        return super.handleExceptionInternal(ex, problemBody, headers, statusCode, request)
    }

    private fun problemEntity(
        request: WebRequest,
        headers: HttpHeaders,
        type: ApiProblemType,
        detail: String,
        extensions: Map<String, Any?> = emptyMap(),
    ): ResponseEntity<Any> {
        val servletRequest = (request as? ServletWebRequest)?.request
            ?: return ResponseEntity.status(type.status).headers(headers).body(null)
        headers.contentType = MediaType.APPLICATION_PROBLEM_JSON
        return ResponseEntity(problemDetail(servletRequest, type, detail, extensions), headers, type.status)
    }

    // ------------------------------------------------------------------
    // Mapped domain exceptions — dispatched via ApiExceptionMappings
    // ------------------------------------------------------------------

    @ExceptionHandler(
        UnsupportedSortException::class,
        UnsupportedSortDirectionException::class,
        ThemeNotFoundException::class,
        TenantNotFoundException::class,
        AttributeNotFoundException::class,
        CodeListNotFoundException::class,
        TemplateNotFoundException::class,
        StencilNotFoundException::class,
        StencilVersionNotFoundException::class,
        StencilVersionNotDraftException::class,
        StencilVersionNotPublishedException::class,
        FontNotFoundException::class,
        GenerationJobNotFoundException::class,
        GenerationJobNotCancellableException::class,
        DocumentNotFoundException::class,
        DraftNotFoundException::class,
        VersionNotDraftException::class,
        VersionNotPublishedException::class,
        VersionArchivedException::class,
        ActivationNotFoundException::class,
        NoActiveVersionException::class,
        ThemeInUseException::class,
        DefaultVariantDeletionException::class,
        VersionStillActiveException::class,
        NoMatchingVariantException::class,
        AmbiguousVariantResolutionException::class,
        AttributeInUseException::class,
        AllowedValuesInUseException::class,
        TemplateVariantNotFoundException::class,
        VersionNotFoundException::class,
        EnvironmentNotFoundException::class,
        NoPublishedVersionException::class,
        DefaultVariantNotFoundException::class,
        AssetNotFoundException::class,
        AssetTooLargeException::class,
        UnsupportedAssetTypeException::class,
        AssetInUseException::class,
        EnvironmentInUseException::class,
        CatalogReadOnlyException::class,
        CatalogNotFoundException::class,
        CatalogNotUpgradeableException::class,
        CatalogSchemaTooNewException::class,
        CatalogSchemaTooOldException::class,
        CatalogSchemaUnknownException::class,
        CodeListInUseException::class,
        CodeListNotRefreshableException::class,
        ApiOperationNotImplementedException::class,
        ContractPublishConflictException::class,
    )
    fun handleMappedApiException(
        ex: Throwable,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val mapping = ApiExceptionMappings.forException(ex)
            ?: throw IllegalStateException("No ApiExceptionMapping registered for ${ex.javaClass.name}")

        logger.warn(mapping.logMessage(ex))

        return problemResponse(
            request = request,
            type = mapping.problemType,
            detail = mapping.detail(ex),
            extensions = mapping.extensions(ex),
        )
    }

    // ------------------------------------------------------------------
    // Validation & batch errors — keep separate because they use
    // custom ProblemDetail builders on the exception itself.
    // ------------------------------------------------------------------

    @ExceptionHandler(BatchValidationException::class)
    fun handleBatchValidationException(ex: BatchValidationException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        logger.warn("Batch validation failed: {}", ex.message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(ex.toProblemDetail(request))
    }

    @ExceptionHandler(DataModelValidationException::class)
    fun handleDataModelValidationException(ex: DataModelValidationException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        logger.warn("Data model validation failed: {} examples with errors", ex.validationErrors.size)
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(ex.toProblemDetail(request))
    }

    @ExceptionHandler(ValidationException::class)
    fun handleValidationException(ex: ValidationException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        logger.warn("Validation failed [{}] for field '{}': {}", ex.code.wire, ex.field, ex.message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(ex.toValidationProblemDetail(request))
    }

    // ------------------------------------------------------------------
    // Auth / security — keep separate because they share problem types
    // and need custom detail strings.
    // ------------------------------------------------------------------

    @ExceptionHandler(TenantAccessDeniedException::class)
    fun handleTenantAccessDeniedException(ex: TenantAccessDeniedException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        logger.warn("Tenant access denied: user={} tenant={}", ex.userEmail, ex.tenantId)
        return problemResponse(
            request,
            ApiProblemTypes.ACCESS_DENIED,
            "Access denied to tenant: ${ex.tenantId.value}",
            extensions = mapOf("tenantId" to ex.tenantId.value),
        )
    }

    @ExceptionHandler(PermissionDeniedException::class)
    fun handlePermissionDeniedException(ex: PermissionDeniedException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        logger.warn("Permission denied: user={} tenant={} permission={}", ex.userEmail, ex.tenantId, ex.permission)
        // Name the missing permission (kebab-case, matching the rest of the API surface) in both the
        // detail string and a machine-readable extension, so an integrator can resolve a 403 without
        // server logs. The permission taxonomy is not a secret; the caller already knows the endpoint.
        val permission = ex.permission.name.lowercase().replace('_', '-')
        return problemResponse(
            request,
            ApiProblemTypes.PERMISSION_DENIED,
            "Missing required permission: $permission",
            extensions = mapOf("requiredPermission" to permission, "tenantId" to ex.tenantId.value),
        )
    }

    @ExceptionHandler(PlatformAccessDeniedException::class)
    fun handlePlatformAccessDeniedException(ex: PlatformAccessDeniedException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        logger.warn("Platform access denied: user={} requiredRole={}", ex.userEmail, ex.requiredRole)
        val role = ex.requiredRole.name.lowercase().replace('_', '-')
        return problemResponse(
            request,
            ApiProblemTypes.PLATFORM_ACCESS_DENIED,
            "Platform role required: $role",
            extensions = mapOf("requiredRole" to role),
        )
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException::class)
    fun handleAccessDeniedException(ex: org.springframework.security.access.AccessDeniedException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        logger.warn("Access denied: {}", ex.message)
        return problemResponse(request, ApiProblemTypes.ACCESS_DENIED, "Access denied")
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException::class)
    fun handleAuthenticationException(ex: org.springframework.security.core.AuthenticationException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        logger.warn("Authentication failed: {}", ex.message)
        return problemResponse(request, ApiProblemTypes.UNAUTHORIZED, "Authentication required")
    }

    // ------------------------------------------------------------------
    // Generic / framework / catch-all
    // ------------------------------------------------------------------

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(ex: IllegalArgumentException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        logger.warn("Bad request: {}", ex.message)
        return problemResponse(request, ApiProblemTypes.BAD_REQUEST, ex.message ?: "Invalid request")
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        // Safety net (#692, REST sibling of UiExceptionFilter's #608 net): an over-length
        // value that slipped past command validation and hit a VARCHAR(n) column throws a
        // PostgreSQL string-truncation (SQLSTATE 22001). Map it to a 400 so it never renders
        // as an opaque 500 — this is the only backstop on the api surface (excluded from
        // UiExceptionFilter) and for import/background writes surfaced here. 22001 carries no
        // column identity, so the message stays form-level rather than a field error, and the
        // raw driver text is never echoed to the caller.
        findStringTruncation(ex)?.let { truncation ->
            logger.warn("Over-length input rejected by the database (SQLSTATE 22001): {}", truncation.message)
            return problemResponse(request, ApiProblemTypes.BAD_REQUEST, "A value in the request is too long.")
        }
        logger.error("Unexpected error in API controller", ex)
        return problemResponse(request, ApiProblemTypes.INTERNAL_ERROR, "An unexpected error occurred")
    }

    /** Walks the full cause chain for a PostgreSQL string-truncation (SQLSTATE 22001). */
    private fun findStringTruncation(ex: Throwable): SQLException? {
        var c: Throwable? = ex
        while (c != null) {
            if (c is SQLException && c.sqlState == "22001") return c
            c = c.cause
        }
        return null
    }
}

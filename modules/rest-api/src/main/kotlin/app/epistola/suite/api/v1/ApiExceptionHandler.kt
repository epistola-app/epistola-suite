// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.error.KnownValidationCodes
import app.epistola.api.model.ValidationError
import app.epistola.suite.api.security.sanitizeForLog
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
import app.epistola.suite.catalog.commands.CatalogBundleException
import app.epistola.suite.catalog.commands.CatalogImportConflictException
import app.epistola.suite.catalog.commands.CatalogImportDependenciesMissingException
import app.epistola.suite.catalog.commands.CatalogNameMismatchException
import app.epistola.suite.catalog.commands.CatalogNameRequiredException
import app.epistola.suite.catalog.commands.CatalogReleaseTakenException
import app.epistola.suite.catalog.commands.CatalogReleaseVersionException
import app.epistola.suite.catalog.commands.CatalogUpgradeConflictException
import app.epistola.suite.catalog.commands.ReleaseRenderingDefaultsTooNewException
import app.epistola.suite.catalog.merge.MergeBaseMissingException
import app.epistola.suite.catalog.merge.MergeConflictsUnresolvedException
import app.epistola.suite.catalog.merge.MergeEntryNotFoundException
import app.epistola.suite.catalog.merge.MergeNotApplicableException
import app.epistola.suite.catalog.merge.MergePreviewOutdatedException
import app.epistola.suite.catalog.merge.MergeRefusedException
import app.epistola.suite.catalog.migrations.CatalogSchemaTooNewException
import app.epistola.suite.catalog.migrations.CatalogSchemaTooOldException
import app.epistola.suite.catalog.migrations.CatalogSchemaUnknownException
import app.epistola.suite.catalog.registry.NamespaceNotGrantedException
import app.epistola.suite.catalog.registry.RegistryArchiveRejectedException
import app.epistola.suite.catalog.registry.RegistryReleaseNotFoundException
import app.epistola.suite.catalog.registry.ReleaseNumberTakenException
import app.epistola.suite.catalog.registry.ReleaseRegistryConflictException
import app.epistola.suite.catalog.registry.ReleaseRegistryNotFoundException
import app.epistola.suite.catalog.releases.CatalogDependenciesUnreleasedException
import app.epistola.suite.catalog.releases.CatalogDraftInUseException
import app.epistola.suite.catalog.releases.CatalogDraftIsNextException
import app.epistola.suite.catalog.releases.CatalogDraftNameInUseException
import app.epistola.suite.catalog.releases.CatalogReleaseBehindException
import app.epistola.suite.catalog.releases.CatalogReleaseHeldException
import app.epistola.suite.catalog.releases.CatalogReleaseIsReleasedException
import app.epistola.suite.catalog.releases.CatalogReleaseNotFoundException
import app.epistola.suite.catalog.revisions.ReleaseInUseException
import app.epistola.suite.catalog.sources.CatalogHasNoSourceException
import app.epistola.suite.catalog.sources.CatalogSourceUnavailableException
import app.epistola.suite.common.ResourceModifiedException
import app.epistola.suite.documents.CatalogNotReleasedException
import app.epistola.suite.documents.DefaultVariantNotFoundException
import app.epistola.suite.documents.DocumentNotFoundException
import app.epistola.suite.documents.EnvironmentNotFoundException
import app.epistola.suite.documents.GenerationJobNotCancellableException
import app.epistola.suite.documents.GenerationJobNotFoundException
import app.epistola.suite.documents.NoPublishedVersionException
import app.epistola.suite.documents.NoReleaseDeployedException
import app.epistola.suite.documents.ReleaseNotFoundException
import app.epistola.suite.documents.TemplateNotInReleaseException
import app.epistola.suite.documents.TemplateVariantNotFoundException
import app.epistola.suite.documents.VersionNotFoundException
import app.epistola.suite.documents.commands.BatchValidationException
import app.epistola.suite.environments.EnvironmentDeploymentRefusedException
import app.epistola.suite.environments.EnvironmentInUseException
import app.epistola.suite.environments.ReleaseDependenciesMissingException
import app.epistola.suite.environments.ReleaseNotDeployableException
import app.epistola.suite.fonts.FontNotFoundException
import app.epistola.suite.security.PermissionDeniedException
import app.epistola.suite.security.PlatformAccessDeniedException
import app.epistola.suite.security.TenantAccessDeniedException
import app.epistola.suite.stencils.StencilInUseException
import app.epistola.suite.stencils.StencilNotFoundException
import app.epistola.suite.stencils.StencilVersionNotFoundException
import app.epistola.suite.templates.TemplateNotFoundException
import app.epistola.suite.templates.commands.variants.DefaultVariantDeletionException
import app.epistola.suite.templates.services.AmbiguousVariantResolutionException
import app.epistola.suite.templates.services.NoMatchingVariantException
import app.epistola.suite.templates.validation.DataModelValidationException
import app.epistola.suite.templates.validation.TemplateDataInvalidException
import app.epistola.suite.tenants.TenantNotFoundException
import app.epistola.suite.themes.ThemeInUseException
import app.epistola.suite.themes.ThemeNotFoundException
import app.epistola.suite.validation.DuplicateIdException
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolation
import jakarta.validation.ConstraintViolationException
import jakarta.validation.ElementKind
import jakarta.validation.Path
import org.apache.tomcat.util.http.InvalidParameterException
import org.slf4j.LoggerFactory
import org.springframework.beans.TypeMismatchException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.web.firewall.RequestRejectedException
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import tools.jackson.databind.DatabindException
import java.sql.SQLException
import app.epistola.api.error.ProblemDetails as ContractProblemDetails

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

    private val log = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

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
            log.warn("Request body rejected, a property is missing or not bindable property={}", field)
            return problemEntity(
                request,
                headers,
                ApiProblemTypes.VALIDATION_ERROR,
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

        log.warn("Unreadable request body reason=\"{}\"", ex.message)

        return problemEntity(request, headers, ApiProblemTypes.BAD_REQUEST, "Request body is malformed or unreadable")
    }

    /**
     * The constraint code of a field a bound body failed: from the Bean Validation violation behind
     * the field error, else from the constraint's name Spring records as the error's code.
     */
    private fun constraintCode(error: FieldError): String? = runCatching { error.unwrap(ConstraintViolation::class.java) }
        .getOrNull()
        ?.let { ApiValidationCodes.of(it) }
        ?: ApiValidationCodes.ofConstraint(error.code, error.rejectedValue)

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
        log.warn("Validation failed on request body errors={}", ex.bindingResult.errorCount)

        val fieldErrors = ex.bindingResult.fieldErrors.map {
            ValidationError(
                field = it.field,
                message = it.defaultMessage ?: "Invalid value",
                rejectedValue = it.rejectedValue?.toString(),
                code = constraintCode(it),
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
            ApiProblemTypes.VALIDATION_ERROR,
            "Request body validation failed",
            mapOf("errors" to fieldErrors + globalErrors),
        )
    }

    /**
     * A required query parameter that is absent: a validation problem naming it, as a parameter
     * that breaks its constraints is ([handleConstraintViolation]).
     */
    override fun handleMissingServletRequestParameter(
        ex: MissingServletRequestParameterException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any> {
        log.warn("Required parameter is missing parameter={}", ex.parameterName)
        return problemEntity(
            request,
            headers,
            ApiProblemTypes.VALIDATION_ERROR,
            "Required parameter '${ex.parameterName}' is missing",
            mapOf("errors" to listOf(ValidationError(field = ex.parameterName, message = "is required", rejectedValue = null, code = KnownValidationCodes.REQUIRED))),
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
        log.warn("Type mismatch for parameter parameter={} expected={} value=\"{}\"", typeMismatch.name, typeMismatch.requiredType?.simpleName, typeMismatch.value)

        val expected = typeMismatch.requiredType?.simpleName
        return problemEntity(
            request,
            headers,
            ApiProblemTypes.VALIDATION_ERROR,
            "Parameter '${typeMismatch.name}' could not be converted to the expected type",
            mapOf(
                "errors" to listOf(
                    ValidationError(
                        field = typeMismatch.name,
                        message = expected?.let { "must be a value of type $it" } ?: "has the wrong type",
                        rejectedValue = typeMismatch.value?.toString(),
                        code = KnownValidationCodes.TYPE,
                    ),
                ),
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

        return super.handleExceptionInternal(ex, problemBody, problemHeaders(headers), statusCode, request)
    }

    /**
     * A writable copy of [headers] that answers `application/problem+json`. The headers Spring
     * hands the [ResponseEntityExceptionHandler] overrides are the exception's own
     * ([org.springframework.web.ErrorResponse.getHeaders]), read-only for most of them:
     * setting the content type on them throws, the handler fails, and the request falls
     * through to Spring Boot's default error body instead of a problem.
     */
    private fun problemHeaders(headers: HttpHeaders): HttpHeaders = HttpHeaders.copyOf(headers).apply {
        contentType = MediaType.APPLICATION_PROBLEM_JSON
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
        return ResponseEntity(problemDetail(servletRequest, type, detail, extensions), problemHeaders(headers), type.status)
    }

    /**
     * A received release renders with rendering defaults this installation lacks (contract
     * `catalog-rendering-defaults-too-new`, 400), built by the contract's own
     * [ContractProblemDetails.catalogRenderingDefaultsTooNew], so its members are the contract's.
     */
    @ExceptionHandler(ReleaseRenderingDefaultsTooNewException::class)
    fun handleRenderingDefaultsTooNew(ex: ReleaseRenderingDefaultsTooNewException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        log.warn("Catalog release refused, rendering defaults too new reason=\"{}\"", ex.message)
        val problem = ContractProblemDetails.catalogRenderingDefaultsTooNew(
            renderingDefaultsVersion = ex.renderingDefaultsVersion,
            supportedVersion = ex.supported,
            detail = ex.message,
            instance = request.problemInstance(),
        )
        problem.title = ApiProblemTypes.CATALOG_RENDERING_DEFAULTS_TOO_NEW.title
        return ResponseEntity.status(problem.status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)
    }

    /**
     * A catalog's source could not be asked (contract `catalog-source-unavailable`): 503, with
     * `Retry-After` when the source gave one, when it could not be reached or did not answer in
     * time; 502 when it answered badly.
     */
    @ExceptionHandler(CatalogSourceUnavailableException::class)
    fun handleCatalogSourceUnavailable(ex: CatalogSourceUnavailableException, request: WebRequest): ResponseEntity<Any> {
        log.warn("Catalog source unavailable reason=\"{}\"", ex.message)
        val headers = HttpHeaders()
        ex.retryAfterSeconds?.let { headers.set(HttpHeaders.RETRY_AFTER, it.toString()) }
        val type = if (ex.badGateway) ApiProblemTypes.CATALOG_SOURCE_UNAVAILABLE.copy(status = HttpStatus.BAD_GATEWAY) else ApiProblemTypes.CATALOG_SOURCE_UNAVAILABLE
        return problemEntity(request, headers, type, ex.message ?: "The catalog's source is unavailable")
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
        StencilInUseException::class,
        FontNotFoundException::class,
        GenerationJobNotFoundException::class,
        GenerationJobNotCancellableException::class,
        DocumentNotFoundException::class,
        ThemeInUseException::class,
        DefaultVariantDeletionException::class,
        NoMatchingVariantException::class,
        AmbiguousVariantResolutionException::class,
        AttributeInUseException::class,
        AllowedValuesInUseException::class,
        TemplateVariantNotFoundException::class,
        VersionNotFoundException::class,
        EnvironmentNotFoundException::class,
        NoPublishedVersionException::class,
        CatalogNotReleasedException::class,
        NoReleaseDeployedException::class,
        ReleaseNotFoundException::class,
        ReleaseNotDeployableException::class,
        ReleaseDependenciesMissingException::class,
        ReleaseInUseException::class,
        TemplateNotInReleaseException::class,
        DefaultVariantNotFoundException::class,
        AssetNotFoundException::class,
        AssetTooLargeException::class,
        UnsupportedAssetTypeException::class,
        AssetInUseException::class,
        EnvironmentInUseException::class,
        CatalogReadOnlyException::class,
        MergeConflictsUnresolvedException::class,
        MergeRefusedException::class,
        MergeNotApplicableException::class,
        MergeEntryNotFoundException::class,
        MergePreviewOutdatedException::class,
        MergeBaseMissingException::class,
        CatalogNotFoundException::class,
        CatalogNotUpgradeableException::class,
        CatalogReleaseVersionException::class,
        app.epistola.suite.catalog.releases.CatalogDraftUnchangedException::class,
        CatalogUpgradeConflictException::class,
        CatalogSchemaTooNewException::class,
        CatalogSchemaTooOldException::class,
        CatalogSchemaUnknownException::class,
        CodeListInUseException::class,
        CodeListNotRefreshableException::class,
        ApiOperationNotImplementedException::class,
        CatalogReleaseNotFoundException::class,
        CatalogReleaseIsReleasedException::class,
        CatalogDraftNameInUseException::class,
        CatalogBundleException::class,
        CatalogDraftIsNextException::class,
        CatalogImportConflictException::class,
        CatalogReleaseTakenException::class,
        CatalogNameRequiredException::class,
        CatalogNameMismatchException::class,
        CatalogImportDependenciesMissingException::class,
        CatalogDraftInUseException::class,
        CatalogDependenciesUnreleasedException::class,
        EnvironmentDeploymentRefusedException::class,
        CatalogHasNoSourceException::class,
        ReleaseRegistryNotFoundException::class,
        ReleaseNumberTakenException::class,
        NamespaceNotGrantedException::class,
        RegistryReleaseNotFoundException::class,
        RegistryArchiveRejectedException::class,
        ReleaseRegistryConflictException::class,
        ResourceModifiedException::class,
        CatalogReleaseBehindException::class,
        CatalogReleaseHeldException::class,
        DuplicateIdException::class,
    )
    fun handleMappedApiException(
        ex: Throwable,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val mapping = ApiExceptionMappings.forException(ex)
            ?: throw IllegalStateException("No ApiExceptionMapping registered for ${ex.javaClass.name}")

        // The mapping's text names ids from the request: an argument, so pseudonymised telemetry leaves it out.
        log.warn("API request refused exception={} reason=\"{}\"", ex.javaClass.simpleName, mapping.logMessage(ex))

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
        log.warn("Batch validation failed reason=\"{}\"", ex.message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(ex.toProblemDetail(request))
    }

    @ExceptionHandler(DataModelValidationException::class)
    fun handleDataModelValidationException(ex: DataModelValidationException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        log.warn("Data model validation failed examplesWithErrors={}", ex.validationErrors.size)
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(ex.toProblemDetail(request))
    }

    @ExceptionHandler(TemplateDataInvalidException::class)
    fun handleTemplateDataInvalidException(ex: TemplateDataInvalidException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        log.warn(
            "Preview data invalid missing={} invalid={}",
            ex.analysis.missingFields.count { it.required },
            ex.analysis.invalidFields.size,
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(ex.toProblemDetail(request))
    }

    /**
     * A path or query parameter that breaks the contract's constraints (its pattern, its length): 400
     * `validation-error`, each failure's `field` the parameter as the contract names it. Method
     * validation of the generated interfaces raises it before the controller runs.
     */
    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(ex: ConstraintViolationException, request: WebRequest): ResponseEntity<Any> {
        log.warn("Parameter validation failed reason=\"{}\"", ex.message)
        val errors = ex.constraintViolations.sortedBy { it.propertyPath.toString() }.map {
            ValidationError(field = parameterName(it), message = it.message ?: "Invalid value", rejectedValue = it.invalidValue?.toString(), code = ApiValidationCodes.of(it))
        }
        return problemEntity(
            request,
            HttpHeaders(),
            ApiProblemTypes.VALIDATION_ERROR,
            "Request parameter validation failed",
            mapOf("errors" to errors),
        )
    }

    /** The name the contract gives the parameter [violation] is about (`@RequestParam`, `@PathVariable`), else its path. */
    private fun parameterName(violation: ConstraintViolation<*>): String {
        val nodes = violation.propertyPath.toList()
        val method = nodes.firstOrNull { it.kind == ElementKind.METHOD }?.name
        val parameter = nodes.firstOrNull { it.kind == ElementKind.PARAMETER }?.`as`(Path.ParameterNode::class.java)
        // The annotations are on the generated interface the controller implements.
        val types = violation.rootBeanClass?.let { listOf(it) + org.springframework.util.ClassUtils.getAllInterfacesForClass(it) }.orEmpty()
        val annotations = parameter?.let { p ->
            types.flatMap { it.methods.toList() }
                .filter { it.name == method && p.parameterIndex < it.parameterCount }
                .flatMap { it.parameters[p.parameterIndex].annotations.toList() }
        }
        return annotations?.firstNotNullOfOrNull {
            when (it) {
                is RequestParam -> it.value.ifEmpty { it.name }
                is PathVariable -> it.value.ifEmpty { it.name }
                else -> null
            }?.takeIf(String::isNotEmpty)
        } ?: violation.propertyPath.toString()
    }

    @ExceptionHandler(ValidationException::class)
    fun handleValidationException(ex: ValidationException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        log.warn("Validation failed code={} field={} reason=\"{}\"", ex.code.wire, ex.field, ex.message)
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
        log.warn("Tenant access denied: user={} tenant={}", ex.userEmail, ex.tenantId)
        return problemResponse(
            request,
            ApiProblemTypes.FORBIDDEN,
            "Access denied to tenant: ${ex.tenantId.value}",
            extensions = mapOf("tenantKey" to ex.tenantId.value),
        )
    }

    @ExceptionHandler(PermissionDeniedException::class)
    fun handlePermissionDeniedException(ex: PermissionDeniedException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        log.warn("Permission denied: user={} tenant={} permission={}", ex.userEmail, ex.tenantId, ex.permission)
        // Name the missing permission as the operation's `x-required-permissions` names it
        // (`TEMPLATE_EDIT`), in the detail and in a member, so an integrator can match the one to
        // the other and resolve a 403 without server logs. The vocabulary is not a secret.
        val permission = ex.permission.name
        return problemResponse(
            request,
            ApiProblemTypes.FORBIDDEN,
            "Missing required permission: $permission",
            extensions = mapOf("requiredPermission" to permission, "tenantKey" to ex.tenantId.value),
        )
    }

    @ExceptionHandler(PlatformAccessDeniedException::class)
    fun handlePlatformAccessDeniedException(ex: PlatformAccessDeniedException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        log.warn("Platform access denied: user={} requiredRole={}", ex.userEmail, ex.requiredRole)
        val role = ex.requiredRole.name
        return problemResponse(
            request,
            ApiProblemTypes.FORBIDDEN,
            "Platform role required: $role",
            extensions = mapOf("requiredPlatformRole" to role),
        )
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException::class)
    fun handleAccessDeniedException(ex: org.springframework.security.access.AccessDeniedException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        log.warn("Access denied reason=\"{}\"", ex.message)
        return problemResponse(request, ApiProblemTypes.FORBIDDEN, "Access denied")
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException::class)
    fun handleAuthenticationException(ex: org.springframework.security.core.AuthenticationException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        log.warn("Authentication failed reason=\"{}\"", ex.message)
        return problemResponse(request, ApiProblemTypes.UNAUTHORIZED, "Authentication required")
    }

    // ------------------------------------------------------------------
    // Generic / framework / catch-all
    // ------------------------------------------------------------------

    /**
     * A query string or form body the container cannot parse into parameters (`?=x`, a parameter
     * with no name): 400 `bad-request`, or the generic 413 when it had too many parameters. Tomcat
     * raises it lazily, when MVC first reads a parameter, so it surfaces here rather than as a
     * container error.
     */
    @ExceptionHandler(InvalidParameterException::class)
    fun handleInvalidParameter(ex: InvalidParameterException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        log.warn("Unparseable request parameters reason=\"{}\"", sanitizeForLog(ex.message.orEmpty()))
        if (ex.errorCode == HttpStatus.CONTENT_TOO_LARGE.value()) {
            return problemResponse(request, ApiProblemTypes.CONTENT_TOO_LARGE, "The request has too many parameters")
        }
        return problemResponse(request, ApiProblemTypes.BAD_REQUEST, "The request's parameters are malformed")
    }

    /**
     * Spring Security's firewall refusing what the request carries: a header name or value, or a
     * parameter, it does not accept (a control character in a value). It checks headers when they
     * are read, so the rejection arrives in the controller, not the filter. A header name Tomcat
     * itself refuses (`"If-Match "`) never gets this far.
     */
    @ExceptionHandler(RequestRejectedException::class)
    fun handleRequestRejected(ex: RequestRejectedException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        log.warn("Request rejected by the firewall reason=\"{}\"", sanitizeForLog(ex.message.orEmpty()))
        return problemResponse(request, ApiProblemTypes.BAD_REQUEST, REQUEST_REJECTED_DETAIL)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(ex: IllegalArgumentException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        log.warn("Bad request reason=\"{}\"", ex.message)
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
        findSqlState(ex, "22001")?.let { truncation ->
            log.warn("Over-length input rejected by the database (SQLSTATE 22001) reason=\"{}\"", truncation.message)
            return problemResponse(request, ApiProblemTypes.BAD_REQUEST, "A value in the request is too long.")
        }
        // A NUL character (`\u0000`) in a string: PostgreSQL stores no NUL in text (SQLSTATE 22021,
        // "invalid byte sequence"). The input is wrong, not the server.
        findSqlState(ex, "22021")?.let { encoding ->
            log.warn("Input the database cannot store rejected (SQLSTATE 22021) reason=\"{}\"", encoding.message)
            return problemResponse(request, ApiProblemTypes.BAD_REQUEST, "A value in the request contains a character that cannot be stored, such as a NUL.")
        }
        log.error("Unexpected error in API controller", ex)
        return problemResponse(request, ApiProblemTypes.INTERNAL_ERROR, "An unexpected error occurred")
    }

    /** Walks the full cause chain for a PostgreSQL error with [sqlState]. */
    private fun findSqlState(ex: Throwable, sqlState: String): SQLException? {
        var c: Throwable? = ex
        while (c != null) {
            if (c is SQLException && c.sqlState == sqlState) return c
            c = c.cause
        }
        return null
    }

    companion object {
        /** The detail of a request the firewall refused; the refused name is logged, never echoed. */
        const val REQUEST_REJECTED_DETAIL = "The request carries a header, parameter or path this server does not accept"
    }
}

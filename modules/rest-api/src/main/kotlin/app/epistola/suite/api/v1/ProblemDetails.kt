package app.epistola.suite.api.v1

import app.epistola.api.model.FieldError
import app.epistola.suite.documents.commands.BatchValidationException
import app.epistola.suite.templates.validation.DataModelValidationException
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import tools.jackson.databind.ObjectMapper
import java.net.URI

const val PROBLEM_TYPE_BASE_URL = "https://epistola.app/errors"

data class ApiProblemType(
    val code: String,
    val title: String,
    val status: HttpStatus,
    val description: String,
    val extensionFields: List<String> = emptyList(),
) {
    val slug: String = code.toProblemSlug()
    val type: URI = URI.create("$PROBLEM_TYPE_BASE_URL/$slug")
}

/**
 * Central registry for all REST API problem types.
 *
 * Validation problem types are generated dynamically from [ValidationCode] entries
 * (see [validationProblemType]) rather than being declared as static constants.
 * This keeps the error taxonomy in one place — the [ValidationCode] enum — and
 * avoids duplication between static and generated types.
 */
object ApiProblemTypes {
    val BATCH_VALIDATION_ERROR = problem("BATCH_VALIDATION_ERROR", "Batch Validation Error", HttpStatus.BAD_REQUEST, "The batch request contains duplicate or inconsistent item-level values.", listOf("code", "errors"))
    val DATA_MODEL_VALIDATION_ERROR = problem("DATA_MODEL_VALIDATION_ERROR", "Data Model Validation Error", HttpStatus.UNPROCESSABLE_ENTITY, "One or more data examples failed validation against the template data schema.", listOf("code", "validationErrors"))
    val BAD_REQUEST = problem("BAD_REQUEST", "Bad Request", HttpStatus.BAD_REQUEST, "The request is invalid and cannot be processed.", listOf("code"))
    val UNAUTHORIZED = problem("UNAUTHORIZED", "Unauthorized", HttpStatus.UNAUTHORIZED, "Authentication is missing, invalid, or expired.", listOf("code"))
    val ACCESS_DENIED = problem("ACCESS_DENIED", "Access Denied", HttpStatus.FORBIDDEN, "The authenticated caller is not allowed to access the requested resource.", listOf("code"))
    val PERMISSION_DENIED = problem("PERMISSION_DENIED", "Permission Denied", HttpStatus.FORBIDDEN, "The caller lacks the required fine-grained permission.", listOf("code"))
    val PLATFORM_ACCESS_DENIED = problem("PLATFORM_ACCESS_DENIED", "Platform Access Denied", HttpStatus.FORBIDDEN, "The caller lacks the required platform role.", listOf("code"))
    val OPERATION_NOT_IMPLEMENTED = problem("OPERATION_NOT_IMPLEMENTED", "Operation Not Implemented", HttpStatus.NOT_IMPLEMENTED, "The requested API operation is part of the contract but has not been implemented by this server yet.", listOf("code", "operation"))
    val INTERNAL_ERROR = problem("INTERNAL_ERROR", "Internal Error", HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected server error occurred.", listOf("code"))
    val METHOD_NOT_ALLOWED = problem("METHOD_NOT_ALLOWED", "Method Not Allowed", HttpStatus.METHOD_NOT_ALLOWED, "The HTTP method is not allowed for the requested resource.", listOf("code", "method", "supportedMethods"))
    val UNSUPPORTED_MEDIA_TYPE = problem("UNSUPPORTED_MEDIA_TYPE", "Unsupported Media Type", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The request body media type is not supported.", listOf("code", "contentType", "supportedTypes"))
    val NOT_ACCEPTABLE = problem("NOT_ACCEPTABLE", "Not Acceptable", HttpStatus.NOT_ACCEPTABLE, "The requested response representation is not available.", listOf("code", "acceptHeader", "supportedTypes"))
    val NOT_FOUND = problem("NOT_FOUND", "Not Found", HttpStatus.NOT_FOUND, "The requested endpoint or resource does not exist.", listOf("code", "path"))
    val MISSING_PARAMETER = problem("MISSING_PARAMETER", "Missing Parameter", HttpStatus.BAD_REQUEST, "A required query or form parameter is missing.", listOf("code", "parameterName", "parameterType"))
    val MISSING_PATH_VARIABLE = problem("MISSING_PATH_VARIABLE", "Missing Path Variable", HttpStatus.BAD_REQUEST, "A required path variable is missing.", listOf("code", "variableName"))
    val TYPE_MISMATCH = problem("TYPE_MISMATCH", "Type Mismatch", HttpStatus.BAD_REQUEST, "A path, query, or form parameter could not be converted to the expected type.", listOf("code", "parameterName", "expectedType", "actualValue"))

    val THEME_NOT_FOUND = problem("THEME_NOT_FOUND", "Theme Not Found", HttpStatus.NOT_FOUND, "The requested theme does not exist or is not visible to the caller.", listOf("code", "themeId"))
    val THEME_IN_USE = problem("THEME_IN_USE", "Theme In Use", HttpStatus.CONFLICT, "The theme cannot be deleted because other resources still reference it.", listOf("code", "themeId"))
    val DEFAULT_VARIANT_DELETION = problem("DEFAULT_VARIANT_DELETION", "Default Variant Deletion", HttpStatus.CONFLICT, "The default variant cannot be deleted.", listOf("code", "variantId"))
    val VERSION_STILL_ACTIVE = problem("VERSION_STILL_ACTIVE", "Version Still Active", HttpStatus.CONFLICT, "The version cannot be archived while it is active in one or more environments.", listOf("code", "versionId", "variantId", "activeEnvironments"))
    val NO_MATCHING_VARIANT = problem("NO_MATCHING_VARIANT", "No Matching Variant", HttpStatus.NOT_FOUND, "No template variant matches the requested criteria.", listOf("code", "templateId", "requiredAttributes"))
    val AMBIGUOUS_VARIANT = problem("AMBIGUOUS_VARIANT", "Ambiguous Variant", HttpStatus.CONFLICT, "Multiple variants match equally well and the API cannot choose one deterministically.", listOf("code", "templateId", "tiedVariants"))
    val ATTRIBUTE_IN_USE = problem("ATTRIBUTE_IN_USE", "Attribute In Use", HttpStatus.CONFLICT, "The attribute cannot be deleted because variants still reference it.", listOf("code", "attributeId", "variantCount"))
    val ALLOWED_VALUES_IN_USE = problem("ALLOWED_VALUES_IN_USE", "Allowed Values In Use", HttpStatus.CONFLICT, "Allowed values cannot be removed while existing variants use them.", listOf("code", "attributeId", "valuesInUse"))
    val TEMPLATE_VARIANT_NOT_FOUND = problem("TEMPLATE_VARIANT_NOT_FOUND", "Template Variant Not Found", HttpStatus.NOT_FOUND, "The requested template variant does not exist or is not visible to the caller.", listOf("code", "tenantId", "templateId", "variantId"))
    val VERSION_NOT_FOUND = problem("VERSION_NOT_FOUND", "Version Not Found", HttpStatus.NOT_FOUND, "The requested version does not exist or is not visible to the caller.", listOf("code", "templateId", "variantId", "versionId"))
    val ENVIRONMENT_NOT_FOUND = problem("ENVIRONMENT_NOT_FOUND", "Environment Not Found", HttpStatus.NOT_FOUND, "The requested environment does not exist or is not visible to the caller.", listOf("code", "tenantId", "environmentId"))
    val NO_PUBLISHED_VERSION = problem("NO_PUBLISHED_VERSION", "No Published Version", HttpStatus.NOT_FOUND, "No published version is available for the requested template and variant.", listOf("code", "tenantId", "templateId", "variantId"))
    val DEFAULT_VARIANT_NOT_FOUND = problem("DEFAULT_VARIANT_NOT_FOUND", "Default Variant Not Found", HttpStatus.NOT_FOUND, "The requested template has no default variant.", listOf("code", "tenantId", "templateId"))
    val ASSET_NOT_FOUND = problem("ASSET_NOT_FOUND", "Asset Not Found", HttpStatus.NOT_FOUND, "The requested asset does not exist or is not visible to the caller.", listOf("code"))
    val ASSET_TOO_LARGE = problem("ASSET_TOO_LARGE", "Asset Too Large", HttpStatus.PAYLOAD_TOO_LARGE, "The uploaded asset exceeds the maximum allowed size.", listOf("code"))
    val UNSUPPORTED_ASSET_TYPE = problem("UNSUPPORTED_ASSET_TYPE", "Unsupported Asset Type", HttpStatus.BAD_REQUEST, "The asset media type is not supported.", listOf("code"))
    val ASSET_IN_USE = problem("ASSET_IN_USE", "Asset In Use", HttpStatus.CONFLICT, "The asset cannot be deleted because template versions still reference it.", listOf("code", "assetId", "usages"))
    val ENVIRONMENT_IN_USE = problem("ENVIRONMENT_IN_USE", "Environment In Use", HttpStatus.CONFLICT, "The environment cannot be deleted because active version activations still reference it.", listOf("code", "environmentId", "activationCount"))
    val CATALOG_READ_ONLY = problem("CATALOG_READ_ONLY", "Catalog Read Only", HttpStatus.CONFLICT, "The catalog is subscribed/read-only and cannot be modified through this API.", listOf("code"))
    val CATALOG_NOT_FOUND = problem("CATALOG_NOT_FOUND", "Catalog Not Found", HttpStatus.NOT_FOUND, "The requested catalog does not exist or is not visible to the caller.", listOf("code", "catalogId"))
    val CATALOG_NOT_UPGRADEABLE = problem("CATALOG_NOT_UPGRADEABLE", "Catalog Not Upgradeable", HttpStatus.CONFLICT, "The requested catalog is not in a state that supports upgrade operations.", listOf("code", "catalogId"))
    val CODE_LIST_IN_USE = problem("CODE_LIST_IN_USE", "Code List In Use", HttpStatus.CONFLICT, "The code list cannot be deleted because an attribute still references it.", listOf("code"))
    val CODE_LIST_NOT_REFRESHABLE = problem("CODE_LIST_NOT_REFRESHABLE", "Code List Not Refreshable", HttpStatus.BAD_REQUEST, "The code list is not URL-sourced and cannot be refreshed.", listOf("code"))
    val TENANT_NOT_FOUND = problem("TENANT_NOT_FOUND", "Tenant Not Found", HttpStatus.NOT_FOUND, "The requested tenant does not exist or is not visible to the caller.", listOf("code", "tenantId"))
    val ATTRIBUTE_NOT_FOUND = problem("ATTRIBUTE_NOT_FOUND", "Attribute Not Found", HttpStatus.NOT_FOUND, "The requested attribute does not exist or is not visible to the caller.", listOf("code", "tenantId", "catalogId", "attributeId"))
    val CODE_LIST_NOT_FOUND = problem("CODE_LIST_NOT_FOUND", "Code List Not Found", HttpStatus.NOT_FOUND, "The requested code list does not exist or is not visible to the caller.", listOf("code", "tenantId", "catalogId", "codeListId"))
    val TEMPLATE_NOT_FOUND = problem("TEMPLATE_NOT_FOUND", "Template Not Found", HttpStatus.NOT_FOUND, "The requested template does not exist or is not visible to the caller.", listOf("code", "tenantId", "templateId"))
    val STENCIL_NOT_FOUND = problem("STENCIL_NOT_FOUND", "Stencil Not Found", HttpStatus.NOT_FOUND, "The requested stencil does not exist or is not visible to the caller.", listOf("code", "tenantId", "stencilId"))
    val STENCIL_VERSION_NOT_FOUND = problem("STENCIL_VERSION_NOT_FOUND", "Stencil Version Not Found", HttpStatus.NOT_FOUND, "The requested stencil version does not exist or is not visible to the caller.", listOf("code", "tenantId", "catalogId", "stencilId", "versionId"))
    val STENCIL_VERSION_NOT_DRAFT = problem("STENCIL_VERSION_NOT_DRAFT", "Stencil Version Not Draft", HttpStatus.CONFLICT, "The requested stencil version is not a draft and cannot be modified.", listOf("code", "tenantId", "catalogId", "stencilId", "versionId"))
    val STENCIL_VERSION_NOT_PUBLISHED = problem("STENCIL_VERSION_NOT_PUBLISHED", "Stencil Version Not Published", HttpStatus.CONFLICT, "The requested stencil version is not published and cannot be archived.", listOf("code", "tenantId", "catalogId", "stencilId", "versionId"))
    val FONT_NOT_FOUND = problem("FONT_NOT_FOUND", "Font Not Found", HttpStatus.NOT_FOUND, "The requested font does not exist or is not visible to the caller.", listOf("code", "tenantId", "catalogId", "fontId"))
    val GENERATION_JOB_NOT_FOUND = problem("GENERATION_JOB_NOT_FOUND", "Generation Job Not Found", HttpStatus.NOT_FOUND, "The requested generation job does not exist or is not visible to the caller.", listOf("code", "tenantId", "requestId"))
    val GENERATION_JOB_NOT_CANCELLABLE = problem("GENERATION_JOB_NOT_CANCELLABLE", "Generation Job Not Cancellable", HttpStatus.CONFLICT, "The generation job cannot be cancelled because it is already completed, failed, or cancelled.", listOf("code", "tenantId", "requestId"))
    val DOCUMENT_NOT_FOUND = problem("DOCUMENT_NOT_FOUND", "Document Not Found", HttpStatus.NOT_FOUND, "The requested document does not exist or is not visible to the caller.", listOf("code", "tenantId", "documentId"))
    val DRAFT_NOT_FOUND = problem("DRAFT_NOT_FOUND", "Draft Not Found", HttpStatus.NOT_FOUND, "No draft exists for the requested variant.", listOf("code", "tenantId", "variantId"))
    val VERSION_NOT_DRAFT = problem("VERSION_NOT_DRAFT", "Version Not Draft", HttpStatus.CONFLICT, "The version is not a draft and cannot be modified.", listOf("code", "tenantId", "versionId"))
    val VERSION_NOT_PUBLISHED = problem("VERSION_NOT_PUBLISHED", "Version Not Published", HttpStatus.CONFLICT, "The version is not published and cannot be archived.", listOf("code", "tenantId", "versionId"))
    val VERSION_ARCHIVED = problem("VERSION_ARCHIVED", "Version Archived", HttpStatus.CONFLICT, "The version is archived and cannot be published.", listOf("code", "tenantId", "versionId"))
    val ACTIVATION_NOT_FOUND = problem("ACTIVATION_NOT_FOUND", "Activation Not Found", HttpStatus.NOT_FOUND, "No activation exists for the requested variant and environment.", listOf("code", "tenantId", "variantId", "environmentId"))
    val NO_ACTIVE_VERSION = problem("NO_ACTIVE_VERSION", "No Active Version", HttpStatus.NOT_FOUND, "No active version is available for the requested variant and environment.", listOf("code", "tenantId", "variantId", "environmentId"))

    val all: List<ApiProblemType> = listOf(
        BATCH_VALIDATION_ERROR,
        DATA_MODEL_VALIDATION_ERROR,
        BAD_REQUEST,
        UNAUTHORIZED,
        ACCESS_DENIED,
        PERMISSION_DENIED,
        PLATFORM_ACCESS_DENIED,
        OPERATION_NOT_IMPLEMENTED,
        INTERNAL_ERROR,
        METHOD_NOT_ALLOWED,
        UNSUPPORTED_MEDIA_TYPE,
        NOT_ACCEPTABLE,
        NOT_FOUND,
        MISSING_PARAMETER,
        MISSING_PATH_VARIABLE,
        TYPE_MISMATCH,
        THEME_NOT_FOUND,
        THEME_IN_USE,
        DEFAULT_VARIANT_DELETION,
        VERSION_STILL_ACTIVE,
        NO_MATCHING_VARIANT,
        AMBIGUOUS_VARIANT,
        ATTRIBUTE_IN_USE,
        ALLOWED_VALUES_IN_USE,
        TEMPLATE_VARIANT_NOT_FOUND,
        VERSION_NOT_FOUND,
        ENVIRONMENT_NOT_FOUND,
        NO_PUBLISHED_VERSION,
        DEFAULT_VARIANT_NOT_FOUND,
        ASSET_NOT_FOUND,
        ASSET_TOO_LARGE,
        UNSUPPORTED_ASSET_TYPE,
        ASSET_IN_USE,
        ENVIRONMENT_IN_USE,
        CATALOG_READ_ONLY,
        CATALOG_NOT_FOUND,
        CATALOG_NOT_UPGRADEABLE,
        CODE_LIST_IN_USE,
        CODE_LIST_NOT_REFRESHABLE,
        TENANT_NOT_FOUND,
        ATTRIBUTE_NOT_FOUND,
        CODE_LIST_NOT_FOUND,
        TEMPLATE_NOT_FOUND,
        STENCIL_NOT_FOUND,
        STENCIL_VERSION_NOT_FOUND,
        STENCIL_VERSION_NOT_DRAFT,
        STENCIL_VERSION_NOT_PUBLISHED,
        FONT_NOT_FOUND,
        GENERATION_JOB_NOT_FOUND,
        GENERATION_JOB_NOT_CANCELLABLE,
        DOCUMENT_NOT_FOUND,
        DRAFT_NOT_FOUND,
        VERSION_NOT_DRAFT,
        VERSION_NOT_PUBLISHED,
        VERSION_ARCHIVED,
        ACTIVATION_NOT_FOUND,
        NO_ACTIVE_VERSION,
    ) + ValidationCode.entries.map { validationProblemType(it) }

    val bySlug: Map<String, ApiProblemType> = all.associateBy { it.slug }

    /**
     * Generates a problem type for a [ValidationCode].
     *
     * This is the single source of truth for validation problem types.
     * There is no static `VALIDATION_ERROR` constant; callers should always
     * use this function, e.g. `validationProblemType(ValidationCode.GENERIC)`.
     */
    fun validationProblemType(code: ValidationCode): ApiProblemType = problem(
        code.wire,
        if (code == ValidationCode.GENERIC) "Validation Error" else code.wire.toProblemTitle(),
        HttpStatus.BAD_REQUEST,
        "The request failed validation with machine-readable code ${code.wire}.",
        listOf("code", "errors"),
    )

    private fun problem(
        code: String,
        title: String,
        status: HttpStatus,
        description: String,
        extensionFields: List<String> = emptyList(),
    ): ApiProblemType = ApiProblemType(code, title, status, description, extensionFields)
}

fun problemDetail(
    request: HttpServletRequest,
    type: ApiProblemType,
    detail: String,
    extensions: Map<String, Any?> = emptyMap(),
): ProblemDetail = ProblemDetail.forStatusAndDetail(type.status, detail).apply {
    this.type = type.type
    this.title = type.title
    this.instance = request.problemInstance()
    setProperty("code", type.code)
    extensions.forEach { (k, v) -> setProperty(k, v) }
}

fun ProblemDetail.toProblemMap(): Map<String, Any?> {
    val body = linkedMapOf<String, Any?>(
        "type" to (type?.toString() ?: "about:blank"),
        "title" to (title ?: ""),
        "status" to status,
        "detail" to (detail ?: ""),
        "instance" to (instance?.toString() ?: ""),
    )
    properties?.forEach { (k, v) -> body[k] = v }
    return body
}

fun HttpServletRequest.problemInstance(): URI = URI.create(requestUriWithQuery())

fun ApiProblemTypes.forStatus(statusCode: HttpStatusCode): ApiProblemType = when (statusCode.value()) {
    400 -> BAD_REQUEST
    401 -> UNAUTHORIZED
    403 -> ACCESS_DENIED
    404 -> NOT_FOUND
    405 -> METHOD_NOT_ALLOWED
    406 -> NOT_ACCEPTABLE
    415 -> UNSUPPORTED_MEDIA_TYPE
    500 -> INTERNAL_ERROR
    501 -> OPERATION_NOT_IMPLEMENTED
    else -> INTERNAL_ERROR
}

fun problemResponse(
    request: HttpServletRequest,
    type: ApiProblemType,
    detail: String,
    extensions: Map<String, Any?> = emptyMap(),
): ResponseEntity<ProblemDetail> {
    val pd = problemDetail(request, type, detail, extensions)
    return ResponseEntity.status(type.status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd)
}

fun problemBody(
    request: HttpServletRequest,
    type: ApiProblemType,
    detail: String,
    extensions: Map<String, Any?> = emptyMap(),
): Map<String, Any?> = problemDetail(request, type, detail, extensions).toProblemMap()

fun ValidationException.toValidationProblemBody(request: HttpServletRequest): Map<String, Any?> = toValidationProblemDetail(request).toProblemMap()

fun ValidationException.toValidationProblemDetail(request: HttpServletRequest): ProblemDetail {
    val type = ApiProblemTypes.validationProblemType(code)
    return problemDetail(
        request = request,
        type = type,
        detail = message,
        extensions = mapOf(
            "errors" to listOf(
                FieldError(
                    field = field,
                    message = message,
                    rejectedValue = null,
                ),
            ),
        ),
    )
}

fun BatchValidationException.toProblemBody(request: HttpServletRequest): Map<String, Any?> = toProblemDetail(request).toProblemMap()

fun BatchValidationException.toProblemDetail(request: HttpServletRequest): ProblemDetail {
    val errors = mutableListOf<FieldError>()
    duplicateCorrelationIds.forEach { correlationId ->
        errors.add(FieldError("correlationId", "Duplicate correlationId in batch: $correlationId", correlationId))
    }
    duplicateFilenames.forEach { filename ->
        errors.add(FieldError("filename", "Duplicate filename in batch: $filename", filename))
    }
    return problemDetail(
        request = request,
        type = ApiProblemTypes.BATCH_VALIDATION_ERROR,
        detail = message ?: "Batch validation failed",
        extensions = mapOf("errors" to errors),
    )
}

fun DataModelValidationException.toProblemBody(request: HttpServletRequest): Map<String, Any?> = toProblemDetail(request).toProblemMap()

fun DataModelValidationException.toProblemDetail(request: HttpServletRequest): ProblemDetail = problemDetail(
    request = request,
    type = ApiProblemTypes.DATA_MODEL_VALIDATION_ERROR,
    detail = "Data examples failed validation against schema",
    extensions = mapOf("validationErrors" to validationErrors),
)

fun writeProblemDetail(
    response: HttpServletResponse,
    objectMapper: ObjectMapper,
    request: HttpServletRequest,
    type: ApiProblemType,
    detail: String,
    extensions: Map<String, Any?> = emptyMap(),
) {
    response.status = type.status.value()
    response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
    // Security filters and AuthenticationEntryPoint write outside MVC, so
    // ApiProblemDetailResponseAdvice does not run. Flatten explicitly here.
    val body = problemDetail(request, type, detail, extensions).toProblemMap()
    objectMapper.writeValue(response.writer, body)
}

fun String.toProblemSlug(): String = lowercase().replace('_', '-')

fun String.toProblemTitle(): String = split('_')
    .joinToString(" ") { part -> part.lowercase().replaceFirstChar { it.titlecase() } }

private fun HttpServletRequest.requestUriWithQuery(): String = if (queryString.isNullOrBlank()) {
    requestURI
} else {
    "$requestURI?$queryString"
}

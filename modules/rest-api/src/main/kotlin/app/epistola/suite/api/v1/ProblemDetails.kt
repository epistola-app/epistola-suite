// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.model.ValidationError
import app.epistola.suite.documents.commands.BatchValidationException
import app.epistola.suite.templates.validation.DataModelValidationException
import app.epistola.suite.templates.validation.TemplateDataInvalidException
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
 * The problem types the REST API answers with.
 *
 * The contract registers them (`x-problem-types`; the server stubs generate the slugs as
 * `app.epistola.api.error.KnownProblemSlugs`) and asks a server to answer with those only: a type
 * names a problem a caller acts on differently, and anything else is the generic type of its
 * status, so a missing theme is `not-found` like any missing resource and a theme in use `conflict`.
 * What tells such problems apart is the `detail` and the extension members, which say what was
 * missing or in the way. `ProblemTypeRegistryTest` fails on a type the contract does not register.
 *
 * The UI keeps finer types of its own, which never reach `/api`: [UiProblemTypes].
 */
object ApiProblemTypes {
    val VALIDATION_ERROR = problem("VALIDATION_ERROR", "Validation Error", HttpStatus.BAD_REQUEST, "The request body or parameters failed validation; `errors[]` carries each failure, its `field` the property or parameter.", listOf("errors"))
    val DATA_MODEL_VALIDATION_ERROR = problem("DATA_MODEL_VALIDATION_ERROR", "Data Model Validation Error", HttpStatus.UNPROCESSABLE_CONTENT, "One or more data examples failed validation against the template data schema.", listOf("validationErrors"))
    val TEMPLATE_DATA_INVALID = problem("TEMPLATE_DATA_INVALID", "Template Data Invalid", HttpStatus.BAD_REQUEST, "The preview data does not satisfy the template's data contract. `errors` lists each field; `missingFields` and `invalidFields` describe what to supply or correct.", listOf("errors", "missingFields", "invalidFields"))
    val BAD_REQUEST = problem("BAD_REQUEST", "Bad Request", HttpStatus.BAD_REQUEST, "The request is malformed or not applicable to the resource's current state, but not a field-level validation failure.", emptyList())
    val UNAUTHORIZED = problem("UNAUTHORIZED", "Unauthorized", HttpStatus.UNAUTHORIZED, "Authentication is missing, invalid, or expired.", emptyList())
    val API_KEY_AUTH_DISABLED = problem("API_KEY_AUTH_DISABLED", "API Key Authentication Disabled", HttpStatus.UNAUTHORIZED, "API-key authentication is disabled for this deployment; use Authorization: Bearer <jwt> instead.", emptyList())

    /**
     * Every refused authorization: the contract registers one 403 type. Which members are present
     * says what was refused: `requiredPermission` and `tenantKey` for a permission lacking in a
     * tenant, `tenantKey` alone for no access to the tenant, `requiredPlatformRole` for a platform
     * role, `reason` when a policy refused. Permissions and roles are named as the contract's
     * `x-required-permissions` names them (`TEMPLATE_EDIT`).
     */
    val FORBIDDEN = problem("FORBIDDEN", "Forbidden", HttpStatus.FORBIDDEN, "The authenticated caller is refused. `requiredPermission` with `tenantKey` names a permission lacking in a tenant, `tenantKey` alone no access to the tenant, `requiredPlatformRole` a platform role, and `reason` a policy refusal.", listOf("requiredPermission", "tenantKey", "requiredPlatformRole", "reason"))
    val NOT_FOUND = problem("NOT_FOUND", "Not Found", HttpStatus.NOT_FOUND, "The addressed endpoint or resource does not exist; the members name what was looked for.", emptyList())
    val CONFLICT = problem("CONFLICT", "Conflict", HttpStatus.CONFLICT, "The request conflicts with the current state of the resource; the members name what is in the way.", emptyList())
    val OPERATION_NOT_IMPLEMENTED = problem("OPERATION_NOT_IMPLEMENTED", "Operation Not Implemented", HttpStatus.NOT_IMPLEMENTED, "The requested API operation is part of the contract but has not been implemented by this server yet.", listOf("operation"))

    // The generic types of the statuses the contract registers no type for yet (contract
    // fix/error-responses); ProblemTypeRegistryTest lists them as pending.
    val METHOD_NOT_ALLOWED = problem("METHOD_NOT_ALLOWED", "Method Not Allowed", HttpStatus.METHOD_NOT_ALLOWED, "The HTTP method is not allowed for the requested resource.", listOf("method", "supportedMethods"))
    val NOT_ACCEPTABLE = problem("NOT_ACCEPTABLE", "Not Acceptable", HttpStatus.NOT_ACCEPTABLE, "The requested response representation is not available.", listOf("acceptHeader", "supportedTypes"))
    val CONTENT_TOO_LARGE = problem("CONTENT_TOO_LARGE", "Content Too Large", HttpStatus.CONTENT_TOO_LARGE, "The request or the content it uploads exceeds the maximum allowed size.", listOf("maxBytes"))
    val UNSUPPORTED_MEDIA_TYPE = problem("UNSUPPORTED_MEDIA_TYPE", "Unsupported Media Type", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The request body media type is not supported.", listOf("contentType", "supportedTypes"))
    val INTERNAL_ERROR = problem("INTERNAL_ERROR", "Internal Error", HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected server error occurred.", emptyList())

    val NO_MATCHING_VARIANT = problem("NO_MATCHING_VARIANT", "No Matching Variant", HttpStatus.NOT_FOUND, "No template variant matches the requested criteria.", listOf("templateId", "requiredAttributes"))
    val AMBIGUOUS_VARIANT = problem("AMBIGUOUS_VARIANT", "Ambiguous Variant", HttpStatus.CONFLICT, "Multiple variants match equally well and the API cannot choose one deterministically.", listOf("templateId", "tiedVariants"))
    val RELEASE_DEPENDENCIES_MISSING = problem("RELEASE_DEPENDENCIES_MISSING", "Release Dependencies Missing", HttpStatus.CONFLICT, "A release cannot be deployed or imported because releases of other catalogs it pins cannot be had; `missingDependencies` lists each one and why.", listOf("missingDependencies"))
    val RELEASE_IN_USE = problem("RELEASE_IN_USE", "Release In Use", HttpStatus.CONFLICT, "The release is deployed to an environment, or another catalog's release renders with it.", listOf("catalogId", "releaseVersion", "environments", "pinnedBy"))
    val CATALOG_READ_ONLY = problem("CATALOG_READ_ONLY", "Catalog Read Only", HttpStatus.CONFLICT, "The catalog cannot be changed in this installation: the installation only receives and runs releases, or Epistola supplies the catalog.", listOf("catalogKey", "readOnlyReason"))
    val MERGE_CONFLICTS_UNRESOLVED = problem("MERGE_CONFLICTS_UNRESOLVED", "Merge Conflicts Unresolved", HttpStatus.CONFLICT, "A resource changed in both the draft and the release has no resolution; `unresolvedConflicts` lists them.", listOf("unresolvedConflicts"))
    val MERGE_REFUSED = problem("MERGE_REFUSED", "Merge Refused", HttpStatus.CONFLICT, "The release removes something the draft still uses.", emptyList())
    val MERGE_BASE_MISSING = problem("MERGE_BASE_MISSING", "Merge Base Missing", HttpStatus.CONFLICT, "This installation does not have the merge base, the release both the draft and the release come from; `version` and `fingerprint` name it, to get first.", listOf("version", "fingerprint"))
    val MERGE_PREVIEW_OUTDATED = problem("MERGE_PREVIEW_OUTDATED", "Merge Preview Outdated", HttpStatus.CONFLICT, "The draft or the release changed since the preview whose token the request sent as expectedToken; preview again.", emptyList())
    val MERGE_NOT_APPLICABLE = problem("MERGE_NOT_APPLICABLE", "Merge Not Applicable", HttpStatus.UNPROCESSABLE_CONTENT, "The draft already contains the release, or a resolution names something that is not a conflict.", emptyList())
    val CATALOG_DRAFT_NAME_IN_USE = problem("CATALOG_DRAFT_NAME_IN_USE", "Catalog Draft Name In Use", HttpStatus.CONFLICT, "Another draft of the catalog has this name; names tell drafts apart.", listOf("catalogKey", "name"))
    val RESOURCE_MODIFIED = problem("RESOURCE_MODIFIED", "Resource Modified", HttpStatus.PRECONDITION_FAILED, "The resource changed since the ETag sent in If-Match was read. Read it again and reapply your change.", listOf("expectedRevision", "currentRevision"))
    val ENVIRONMENT_DEPLOYMENT_REFUSED = problem("ENVIRONMENT_DEPLOYMENT_REFUSED", "Environment Deployment Refused", HttpStatus.CONFLICT, "The environment cannot serve that: a production environment serves only releases, never a draft.", emptyList())
    val CATALOG_SOURCE_UNAVAILABLE = problem("CATALOG_SOURCE_UNAVAILABLE", "Catalog Source Unavailable", HttpStatus.SERVICE_UNAVAILABLE, "The catalog's source could not be reached or did not answer in time (503), or answered badly (502).", emptyList())
    val RELEASE_REGISTRY_NOT_FOUND = problem("RELEASE_REGISTRY_NOT_FOUND", "Release Registry Not Found", HttpStatus.NOT_FOUND, "This installation has no release registry of that name: none configured, and not connected to an Exchange of that name.", emptyList())
    val RELEASE_NUMBER_TAKEN = problem("RELEASE_NUMBER_TAKEN", "Release Number Taken", HttpStatus.CONFLICT, "The release registry, or the tenant importing it, holds this version with other content, or this release id as another release; one number and one id name one release. Release the changes under a new version.", emptyList())
    val NAMESPACE_NOT_GRANTED = problem("NAMESPACE_NOT_GRANTED", "Namespace Not Granted", HttpStatus.CONFLICT, "The release registry (the Exchange) does not grant this installation the catalog's namespace.", emptyList())
    val RELEASE_DEPENDENCIES_UNRELEASED = problem("RELEASE_DEPENDENCIES_UNRELEASED", "Release Dependencies Unreleased", HttpStatus.CONFLICT, "The draft's dependency declarations name drafts of other catalogs and the request does not release them along (`releaseDependencies`); `unreleasedDependencies` lists each.", listOf("unreleasedDependencies"))
    val CATALOG_VERSION_BEHIND = problem("CATALOG_VERSION_BEHIND", "Catalog Version Behind", HttpStatus.CONFLICT, "The draft is behind releases it does not contain; name every one in acknowledgedBehind to release it.", listOf("catalogKey", "version", "behind"))
    val CATALOG_SCHEMA_TOO_NEW = problem("CATALOG_SCHEMA_TOO_NEW", "Catalog Wire Schema Too New", HttpStatus.BAD_REQUEST, "The catalog was exported by a newer Epistola than this instance can read; upgrade this instance.", listOf("version", "supportedVersion"))
    val CATALOG_RENDERING_DEFAULTS_TOO_NEW = problem("CATALOG_RENDERING_DEFAULTS_TOO_NEW", "Catalog Rendering Defaults Too New", HttpStatus.BAD_REQUEST, "The release renders with rendering defaults this instance does not have; upgrade this instance to receive it.", listOf("renderingDefaultsVersion", "supportedVersion"))
    val CATALOG_SCHEMA_TOO_OLD = problem("CATALOG_SCHEMA_TOO_OLD", "Catalog Wire Schema Too Old", HttpStatus.BAD_REQUEST, "The catalog's wire format predates the oldest version this instance can upgrade; re-export from a current source.", listOf("version", "baselineVersion"))
    val CATALOG_SCHEMA_UNKNOWN = problem("CATALOG_SCHEMA_UNKNOWN", "Catalog Wire Schema Unrecognised", HttpStatus.BAD_REQUEST, "The uploaded payload is not a recognised catalog wire format (not valid JSON, or a missing or non-integer schemaVersion).", emptyList())
    val GENERATION_JOB_NOT_CANCELLABLE = problem("GENERATION_JOB_NOT_CANCELLABLE", "Generation Job Not Cancellable", HttpStatus.CONFLICT, "The generation job cannot be cancelled because it is already completed, failed, or cancelled.", listOf("tenantId", "requestId"))

    val all: List<ApiProblemType> = listOf(
        VALIDATION_ERROR,
        DATA_MODEL_VALIDATION_ERROR,
        TEMPLATE_DATA_INVALID,
        BAD_REQUEST,
        UNAUTHORIZED,
        API_KEY_AUTH_DISABLED,
        FORBIDDEN,
        NOT_FOUND,
        CONFLICT,
        OPERATION_NOT_IMPLEMENTED,
        METHOD_NOT_ALLOWED,
        NOT_ACCEPTABLE,
        CONTENT_TOO_LARGE,
        UNSUPPORTED_MEDIA_TYPE,
        INTERNAL_ERROR,
        NO_MATCHING_VARIANT,
        AMBIGUOUS_VARIANT,
        RELEASE_DEPENDENCIES_MISSING,
        RELEASE_IN_USE,
        CATALOG_READ_ONLY,
        MERGE_CONFLICTS_UNRESOLVED,
        MERGE_REFUSED,
        MERGE_BASE_MISSING,
        MERGE_PREVIEW_OUTDATED,
        MERGE_NOT_APPLICABLE,
        CATALOG_DRAFT_NAME_IN_USE,
        RESOURCE_MODIFIED,
        ENVIRONMENT_DEPLOYMENT_REFUSED,
        CATALOG_SOURCE_UNAVAILABLE,
        RELEASE_REGISTRY_NOT_FOUND,
        RELEASE_NUMBER_TAKEN,
        NAMESPACE_NOT_GRANTED,
        RELEASE_DEPENDENCIES_UNRELEASED,
        CATALOG_VERSION_BEHIND,
        CATALOG_SCHEMA_TOO_NEW,
        CATALOG_RENDERING_DEFAULTS_TOO_NEW,
        CATALOG_SCHEMA_TOO_OLD,
        CATALOG_SCHEMA_UNKNOWN,
        GENERATION_JOB_NOT_CANCELLABLE,
    )

    val bySlug: Map<String, ApiProblemType> = all.associateBy { it.slug }
}

/**
 * Problem types the web UI answers with on its own routes, finer than the API's: the editor
 * tells a save into a released version (`catalog-version-released`) and a binding's validation
 * code (`node-parameter-binding-syntax-invalid`) apart by them. They are never put on `/api`,
 * where the contract allows only [ApiProblemTypes]; the same failure there is the generic type of
 * its status.
 */
object UiProblemTypes {
    val CATALOG_VERSION_NOT_FOUND = problem("CATALOG_VERSION_NOT_FOUND", "Catalog Version Not Found", HttpStatus.NOT_FOUND, "The catalog has no such version: no release with this number, or no such draft.", listOf("catalogKey", "version"))
    val CATALOG_VERSION_RELEASED = problem("CATALOG_VERSION_RELEASED", "Catalog Version Released", HttpStatus.CONFLICT, "The catalog version is released and cannot change; change a draft.", listOf("catalogKey", "version"))
    val CATALOG_VERSION_HELD = problem("CATALOG_VERSION_HELD", "Catalog Version Held", HttpStatus.CONFLICT, "The draft alone holds resources something outside it still uses; change those first.", listOf("catalogKey", "version", "heldBy"))

    /**
     * The type of a [ValidationException] with [code] on a UI route: its code as the slug, which the
     * editor matches on. [ValidationCode.GENERIC] is the API's [ApiProblemTypes.VALIDATION_ERROR].
     */
    fun validation(code: ValidationCode): ApiProblemType = if (code == ValidationCode.GENERIC) {
        ApiProblemTypes.VALIDATION_ERROR
    } else {
        problem(code.wire, code.wire.toProblemTitle(), HttpStatus.BAD_REQUEST, "The request failed validation with machine-readable code ${code.wire}.", listOf("errors"))
    }

    val all: List<ApiProblemType> = listOf(CATALOG_VERSION_NOT_FOUND, CATALOG_VERSION_RELEASED, CATALOG_VERSION_HELD) +
        ValidationCode.entries.filter { it != ValidationCode.GENERIC }.map { validation(it) }

    val bySlug: Map<String, ApiProblemType> = all.associateBy { it.slug }
}

private fun problem(
    code: String,
    title: String,
    status: HttpStatus,
    description: String,
    extensionFields: List<String> = emptyList(),
): ApiProblemType = ApiProblemType(code, title, status, description, extensionFields)

fun problemDetail(
    request: HttpServletRequest,
    type: ApiProblemType,
    detail: String,
    extensions: Map<String, Any?> = emptyMap(),
): ProblemDetail = ProblemDetail.forStatusAndDetail(type.status, detail).apply {
    this.type = type.type
    this.title = type.title
    this.instance = request.problemInstance()
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
    // A mapping may set an optional member to null for an occurrence that lacks it (a template
    // missing from a release names no variant). ProblemDetail declares its property values non-null,
    // so read them as nullable — Kotlin would otherwise null-check each one and throw — and omit a
    // null member rather than write it.
    val members: Map<String, Any?> = properties.orEmpty()
    members.forEach { (k, v) -> if (v != null) body[k] = v }
    return body
}

/**
 * The request's path and query as the problem's `instance`. A request the firewall or the container
 * refused can carry characters a URI may not hold; those are quoted rather than fail the problem.
 */
fun HttpServletRequest.problemInstance(): URI = runCatching { URI.create(requestUriWithQuery()) }
    .recoverCatching { URI(null, null, requestURI, queryString, null) }
    .getOrElse { URI.create("") }

/**
 * The generic type of [statusCode], for a framework error with no type of its own. A client error
 * the contract has no generic type for (412, 422, 429) is `bad-request`, a server error
 * `internal-error`; the status stays the one the framework chose.
 */
fun ApiProblemTypes.forStatus(statusCode: HttpStatusCode): ApiProblemType = when (statusCode.value()) {
    400 -> BAD_REQUEST
    401 -> UNAUTHORIZED
    403 -> FORBIDDEN
    404 -> NOT_FOUND
    405 -> METHOD_NOT_ALLOWED
    406 -> NOT_ACCEPTABLE
    409 -> CONFLICT
    413 -> CONTENT_TOO_LARGE
    415 -> UNSUPPORTED_MEDIA_TYPE
    501 -> OPERATION_NOT_IMPLEMENTED
    else -> if (statusCode.is4xxClientError) BAD_REQUEST else INTERNAL_ERROR
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

/**
 * The problem a UI route answers a [ValidationException] with: typed by its code
 * ([UiProblemTypes.validation]), which the editor matches on. Never for `/api`, which answers
 * [toValidationProblemDetail].
 */
fun ValidationException.toUiValidationProblemBody(request: HttpServletRequest): Map<String, Any?> = toValidationProblemDetail(request, UiProblemTypes.validation(code)).toProblemMap()

/** The API's problem for a [ValidationException]: `validation-error`, whatever its code. */
fun ValidationException.toValidationProblemDetail(
    request: HttpServletRequest,
    type: ApiProblemType = ApiProblemTypes.VALIDATION_ERROR,
): ProblemDetail = problemDetail(
    request = request,
    type = type,
    detail = message,
    extensions = mapOf(
        "errors" to listOf(
            ValidationError(
                field = field,
                message = message,
                rejectedValue = null,
            ),
        ),
    ),
)

fun BatchValidationException.toProblemBody(request: HttpServletRequest): Map<String, Any?> = toProblemDetail(request).toProblemMap()

fun BatchValidationException.toProblemDetail(request: HttpServletRequest): ProblemDetail {
    val errors = mutableListOf<ValidationError>()
    duplicateCorrelationIds.forEach { correlationId ->
        errors.add(ValidationError("correlationId", "Duplicate correlationId in batch: $correlationId", correlationId))
    }
    duplicateFilenames.forEach { filename ->
        errors.add(ValidationError("filename", "Duplicate filename in batch: $filename", filename))
    }
    return problemDetail(
        request = request,
        type = ApiProblemTypes.VALIDATION_ERROR,
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

/**
 * The contract's validation problem (`errors[]`, one entry per field, `field` a JSON Pointer into the
 * request body) plus the full analysis. The analysis paths are JSON Pointers into `data`.
 */
fun TemplateDataInvalidException.toProblemDetail(request: HttpServletRequest): ProblemDetail {
    val errors = analysis.missingFields.filter { it.required }.map { field ->
        ValidationError(field = "/data${field.path}", message = "is required", rejectedValue = null)
    } + analysis.invalidFields.map { field ->
        ValidationError(
            field = "/data${field.path}",
            message = field.message,
            rejectedValue = data.at(field.path).takeUnless { it.isMissingNode },
        )
    }
    return problemDetail(
        request = request,
        type = ApiProblemTypes.TEMPLATE_DATA_INVALID,
        detail = message ?: "Data validation failed",
        extensions = mapOf(
            "errors" to errors,
            "missingFields" to analysis.missingFields,
            "invalidFields" to analysis.invalidFields,
        ),
    )
}

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

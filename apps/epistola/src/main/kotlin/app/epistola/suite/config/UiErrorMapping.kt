package app.epistola.suite.config

import app.epistola.suite.api.v1.ApiProblemType
import app.epistola.suite.api.v1.ApiProblemTypes
import org.slf4j.LoggerFactory

/** A resolved UI error: the HTTP status, the problem `type`/`title`, and the user-facing detail. */
data class UiError(val status: Int, val type: ApiProblemType, val detail: String)

/**
 * Request header by which an HTMX form declares where its general/server errors should render
 * (the id of an in-dialog region). Set on each create `<dialog>` via `hx-headers`, so every form
 * inside inherits it and it is absent on all other requests — which therefore keep the default
 * `problem+json` / error-page behaviour.
 */
const val ERROR_REGION_HEADER = "X-Epistola-Error-Region"

private val errorLog = LoggerFactory.getLogger("app.epistola.suite.config.UiErrorMapping")

/** Unwrap nested exceptions (e.g. Spring's `NestedServletException`) to the root cause. */
fun unwrapCause(ex: Throwable): Throwable = ex.cause?.let { unwrapCause(it) } ?: ex

/**
 * Maps a thrown exception to its status, problem type, and user-facing detail — the single source
 * of truth shared by [UiHandlerExceptionResolver] (handler-thrown errors, rendered during dispatch)
 * and [UiExceptionFilter] (whatever escapes the dispatch). Matched by simple class name to avoid
 * coupling to every domain module. The status is the one this UI surface returns (e.g. a read-only
 * catalog → 403), independent of the problem type's canonical REST status.
 *
 * Framework pre-dispatch errors (unsupported method/media type, not-found, upload-too-large) are
 * **not** mapped here — they carry their own extensions and are handled in the resolver.
 */
fun resolveUiError(cause: Throwable): UiError = when (cause::class.simpleName) {
    "TenantAccessDeniedException" -> {
        errorLog.warn("Tenant access denied: {}", cause.message)
        UiError(403, ApiProblemTypes.ACCESS_DENIED, "You don't have access to this tenant.")
    }
    "PermissionDeniedException" -> {
        errorLog.warn("Permission denied: {}", cause.message)
        UiError(403, ApiProblemTypes.PERMISSION_DENIED, "You don't have permission to perform this action.")
    }
    "PlatformAccessDeniedException" -> {
        errorLog.warn("Platform access denied: {}", cause.message)
        UiError(403, ApiProblemTypes.PLATFORM_ACCESS_DENIED, "This action requires platform administrator access.")
    }
    "CatalogReadOnlyException" -> {
        errorLog.warn("Catalog read-only: {}", cause.message)
        UiError(409, ApiProblemTypes.CATALOG_READ_ONLY, cause.message ?: "This catalog is read-only.")
    }
    // A general (non-field) form error the user must fix — handlers throw it instead of
    // hand-rendering, so a create dialog shows it in its general error region.
    "FormInputException" -> UiError(400, ApiProblemTypes.BAD_REQUEST, cause.message ?: "Invalid input.")
    "AssetTooLargeException" -> {
        errorLog.warn("Asset too large: {}", cause.message)
        UiError(413, ApiProblemTypes.ASSET_TOO_LARGE, cause.message ?: "The uploaded file is too large.")
    }
    "UnsupportedAssetTypeException" -> {
        errorLog.warn("Unsupported asset type: {}", cause.message)
        UiError(400, ApiProblemTypes.UNSUPPORTED_ASSET_TYPE, cause.message ?: "Unsupported file type.")
    }
    else -> {
        errorLog.error("Unhandled exception on UI request: {} {}", cause::class.simpleName, cause.message, cause)
        UiError(500, ApiProblemTypes.INTERNAL_ERROR, "An unexpected error occurred.")
    }
}

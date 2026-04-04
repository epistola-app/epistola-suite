package app.epistola.suite.htmx

import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.FeedbackId
import app.epistola.suite.common.ids.FeedbackKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import org.springframework.web.servlet.function.ServerRequest

/**
 * Extension properties for detecting and reading HTMX request headers.
 *
 * @see <a href="https://htmx.org/reference/#request_headers">HTMX Request Headers</a>
 */

/** True if this request was made by HTMX (contains HX-Request header). */
val ServerRequest.isHtmx: Boolean
    get() = headers().firstHeader("HX-Request") == "true"

/** The ID of the element that triggered the request. */
val ServerRequest.htmxTrigger: String?
    get() = headers().firstHeader("HX-Trigger")

/** The name of the element that triggered the request. */
val ServerRequest.htmxTriggerName: String?
    get() = headers().firstHeader("HX-Trigger-Name")

/** The ID of the target element. */
val ServerRequest.htmxTarget: String?
    get() = headers().firstHeader("HX-Target")

/** The current URL of the browser. */
val ServerRequest.htmxCurrentUrl: String?
    get() = headers().firstHeader("HX-Current-URL")

/** True if the request is for history restoration after a miss in the local history cache. */
val ServerRequest.htmxHistoryRestoreRequest: Boolean
    get() = headers().firstHeader("HX-History-Restore-Request") == "true"

/** The user response to an hx-prompt. */
val ServerRequest.htmxPrompt: String?
    get() = headers().firstHeader("HX-Prompt")

/** True if the request is via an element using hx-boost. */
val ServerRequest.htmxBoosted: Boolean
    get() = headers().firstHeader("HX-Boosted") == "true"

/**
 * Extract a path variable and parse it with a validator function.
 * Returns null if parsing fails.
 *
 * Usage:
 * ```kotlin
 * val themeId = request.pathId("themeId") { ThemeId.validateOrNull(it) }
 *     ?: return ServerResponse.badRequest().build()
 * ```
 */
fun <T> ServerRequest.pathId(name: String, parse: (String) -> T?): T? = parse(pathVariable(name))

// -- Composite ID extraction from path variables ---------------------------------------------------

/**
 * Extract a [TenantId] from the `tenantId` path variable.
 */
fun ServerRequest.tenantId(): TenantId = TenantId(TenantKey.of(pathVariable("tenantId")))

/**
 * Extract and validate a [TemplateId] from the `id` path variable.
 * Returns null if the template key is invalid.
 */
fun ServerRequest.templateId(tenantId: TenantId): TemplateId? {
    val key = TemplateKey.validateOrNull(pathVariable("id")) ?: return null
    return TemplateId(key, tenantId)
}

/**
 * Extract and validate a [VariantId] from the `variantId` path variable.
 * Returns null if the variant key is invalid.
 */
fun ServerRequest.variantId(templateId: TemplateId): VariantId? {
    val key = VariantKey.validateOrNull(pathVariable("variantId")) ?: return null
    return VariantId(key, templateId)
}

/**
 * Extract and validate a [VersionId] from the `versionId` path variable.
 * Returns null if the version number is invalid or out of range.
 */
fun ServerRequest.versionId(variantId: VariantId): VersionId? {
    val versionInt = pathVariable("versionId").toIntOrNull() ?: return null
    if (versionInt !in VersionKey.MIN_VERSION..VersionKey.MAX_VERSION) return null
    return VersionId(VersionKey.of(versionInt), variantId)
}

/**
 * Extract and validate a [ThemeId] from the `themeId` path variable.
 * Returns null if the theme key is invalid.
 */
fun ServerRequest.themeId(tenantId: TenantId): ThemeId? {
    val key = ThemeKey.validateOrNull(pathVariable("themeId")) ?: return null
    return ThemeId(key, tenantId)
}

/**
 * Extract and validate a [StencilId] from the `stencilId` path variable.
 * Returns null if the stencil key is invalid.
 */
fun ServerRequest.stencilId(tenantId: TenantId): StencilId? {
    val key = StencilKey.validateOrNull(pathVariable("stencilId")) ?: return null
    return StencilId(key, tenantId)
}

/**
 * Extract and validate an [EnvironmentId] from the `environmentId` path variable.
 * Returns null if the environment key is invalid.
 */
fun ServerRequest.environmentId(tenantId: TenantId): EnvironmentId? {
    val key = EnvironmentKey.validateOrNull(pathVariable("environmentId")) ?: return null
    return EnvironmentId(key, tenantId)
}

/**
 * Extract and validate an [AttributeId] from the `attributeId` path variable.
 * Returns null if the attribute key is invalid.
 */
fun ServerRequest.attributeId(tenantId: TenantId): AttributeId? {
    val key = AttributeKey.validateOrNull(pathVariable("attributeId")) ?: return null
    return AttributeId(key, tenantId)
}

/**
 * Extract and validate a [FeedbackId] from the `feedbackId` path variable.
 * Returns null if the feedback UUID is invalid.
 */
fun ServerRequest.feedbackId(tenantId: TenantId): FeedbackId? {
    val uuid = runCatching { java.util.UUID.fromString(pathVariable("feedbackId")) }.getOrNull() ?: return null
    return FeedbackId(FeedbackKey.of(uuid), tenantId)
}

/**
 * Get a query parameter with optional default value.
 *
 * Usage:
 * ```kotlin
 * val searchTerm = request.queryParam("q")  // returns null if not present
 * val category = request.queryParam("category", "all")  // returns "all" if not present
 * ```
 */
fun ServerRequest.queryParam(name: String): String? = param(name).orElse(null)

fun ServerRequest.queryParam(name: String, default: String): String = param(name).orElse(default)

/**
 * Get a query parameter as an integer with optional default value.
 *
 * Usage:
 * ```kotlin
 * val offset = request.queryParamInt("offset", 0)
 * val limit = request.queryParamInt("limit", 100)
 * ```
 */
fun ServerRequest.queryParamInt(name: String, default: Int): Int = param(name).orElse(null)?.toIntOrNull() ?: default

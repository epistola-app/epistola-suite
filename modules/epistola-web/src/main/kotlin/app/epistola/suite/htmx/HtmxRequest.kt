// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.htmx

import app.epistola.suite.catalog.versions.CatalogVersionNotFoundException
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.DraftRef
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
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
import java.util.UUID

/*
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
 * The response shape expected for this request.
 *
 * `FRAGMENT` is the normal, non-boosted HTMX mode used by explicit `hx-get` /
 * `hx-post` interactions. The other modes must receive full pages: boosted
 * navigation swaps the document body, history restore re-fetches a whole URL,
 * and plain requests are the progressive-enhancement fallback.
 */
enum class HtmxRequestMode {
    PLAIN,
    BOOSTED,
    HISTORY_RESTORE,
    FRAGMENT,
}

val ServerRequest.htmxRequestMode: HtmxRequestMode
    get() = when {
        !isHtmx -> HtmxRequestMode.PLAIN
        htmxHistoryRestoreRequest -> HtmxRequestMode.HISTORY_RESTORE
        htmxBoosted -> HtmxRequestMode.BOOSTED
        else -> HtmxRequestMode.FRAGMENT
    }

val ServerRequest.wantsFragmentResponse: Boolean
    get() = htmxRequestMode == HtmxRequestMode.FRAGMENT

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
 * A catalog in a UI route, optionally one of its drafts: `letters` for its next version, or
 * `letters@<draft id>` for long-term work of `letters` (contract ADR 0002, amendment 2). A draft has no
 * number until it is released, so its id names it.
 *
 * The draft travels in the catalog's own path segment (or `catalog` parameter) rather than in the
 * session, so a link to a draft is shareable, and every link, HTMX request and editor call built from
 * the segment stays on that draft. Without one the route works on the catalog's current version: its
 * next version. `@` cannot occur in a catalog key, so the two never collide.
 */
data class CatalogSegment(val key: CatalogKey, val draft: UUID? = null) {
    /** The segment as it appears in a URL. */
    val value: String get() = draft?.let { "${key.value}@$it" } ?: key.value

    /** The draft this names, for a message's `catalogVersion`; null for the catalog's current version. */
    fun ref(tenantKey: TenantKey): CatalogVersionRef? = draft?.let { CatalogVersionRef(tenantKey, key, DraftRef.Id(it)) }

    /** The same catalog at long-term work [draft], or at its current version when that is null. */
    fun at(draft: UUID?): CatalogSegment = copy(draft = draft)

    /**
     * The segment to link a resource of catalog [catalogKey] with: this one when it is the same
     * catalog, so a list filtered to a draft links its rows into the draft, else the plain key.
     */
    fun linkFor(catalogKey: Any?): String = catalogKey?.toString()?.takeIf { it != key.value } ?: value

    override fun toString(): String = value

    companion object {
        /**
         * Parses `key` or `key@<draft id>`. Throws [IllegalArgumentException] for an invalid key, and
         * [CatalogVersionNotFoundException] for a draft part that names no draft: a link from before
         * drafts lost their numbers (`letters@1.0.1`) is a draft that is not there, not a bad request.
         */
        fun parse(raw: String): CatalogSegment {
            val at = raw.indexOf('@')
            if (at < 0) return CatalogSegment(CatalogKey.of(raw))
            val key = CatalogKey.of(raw.substring(0, at))
            val draft = raw.substring(at + 1).trim()
            val id = runCatching { UUID.fromString(draft) }.getOrNull()?.takeIf { it.toString() == draft.lowercase() }
                ?: throw CatalogVersionNotFoundException(key, draft)
            return CatalogSegment(key, id)
        }

        fun parseOrNull(raw: String?): CatalogSegment? = raw?.takeIf { it.isNotBlank() }?.let { runCatching { parse(it.trim()) }.getOrNull() }
    }
}

/** The `catalogId` path variable: the catalog, and the draft it names if any. */
fun ServerRequest.catalogSegment(): CatalogSegment = CatalogSegment.parse(pathVariable("catalogId"))

/**
 * Extract a [CatalogKey] from the `catalogId` path variable (without the `@version` of a draft).
 */
fun ServerRequest.catalogId(): CatalogKey = catalogSegment().key

/**
 * Extract a [CatalogKey] from the `catalogId` path variable, or null if not present.
 */
fun ServerRequest.catalogIdOrNull(): CatalogKey? = try {
    catalogSegment().key
} catch (_: IllegalArgumentException) {
    null
} catch (e: CatalogVersionNotFoundException) {
    e.catalogKey
}

/**
 * The draft the `catalogId` path variable names (`letters@<draft id>`), for a message's
 * `catalogVersion`; null when it names none, which means the catalog's next version.
 */
fun ServerRequest.catalogVersion(): CatalogVersionRef? = catalogSegment().ref(tenantId().key)

/**
 * The `catalog` filter or form parameter, which may name a draft as `letters@<draft id>`; null when absent
 * or blank.
 */
fun ServerRequest.catalogParam(name: String = "catalog"): CatalogSegment? = param(name).orElse(null)?.ifBlank { null }?.let { CatalogSegment.parse(it.trim()) }

/**
 * Extract and validate a [TemplateId] from the `id` and `catalogId` path variables.
 * Returns null if the template key is invalid.
 */
fun ServerRequest.templateId(tenantId: TenantId): TemplateId? {
    val catalog = CatalogId(catalogId(), tenantId)
    val key = TemplateKey.validateOrNull(pathVariable("id")) ?: return null
    return TemplateId(key, catalog)
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
 * Extract and validate a [ThemeId] from the `themeId` and `catalogId` path variables.
 * Returns null if the theme key is invalid.
 */
fun ServerRequest.themeId(tenantId: TenantId): ThemeId? {
    val catalog = CatalogId(catalogId(), tenantId)
    val key = ThemeKey.validateOrNull(pathVariable("themeId")) ?: return null
    return ThemeId(key, catalog)
}

/**
 * Extract and validate a [StencilId] from the `stencilId` and `catalogId` path variables.
 * Returns null if the stencil key is invalid.
 */
fun ServerRequest.stencilId(tenantId: TenantId): StencilId? {
    val catalog = CatalogId(catalogId(), tenantId)
    val key = StencilKey.validateOrNull(pathVariable("stencilId")) ?: return null
    return StencilId(key, catalog)
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
 * Extract and validate an [AttributeId] from the `attributeId` and `catalogId` path variables.
 * Returns null if the attribute key is invalid.
 */
fun ServerRequest.attributeId(tenantId: TenantId): AttributeId? {
    val catalog = CatalogId(catalogId(), tenantId)
    val key = AttributeKey.validateOrNull(pathVariable("attributeId")) ?: return null
    return AttributeId(key, catalog)
}

/**
 * Extract and validate a [CodeListId] from the `codeListId` and `catalogId` path variables.
 * Returns null if the slug is invalid.
 */
fun ServerRequest.codeListId(tenantId: TenantId): CodeListId? {
    val catalog = CatalogId(catalogId(), tenantId)
    val key = CodeListKey.validateOrNull(pathVariable("codeListId")) ?: return null
    return CodeListId(key, catalog)
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

/**
 * The revision an edit form was opened at (its hidden `expectedRevision` field), for optimistic
 * locking; null when the form sends none, so the write is last write wins.
 */
fun ServerRequest.expectedRevision(): Long? = param("expectedRevision").orElse(null)?.toLongOrNull()

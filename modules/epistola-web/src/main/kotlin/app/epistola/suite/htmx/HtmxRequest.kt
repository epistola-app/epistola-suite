// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.htmx

import app.epistola.catalog.protocol.CatalogNamespaces
import app.epistola.suite.catalog.releases.CatalogReleaseNotFoundException
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.DraftRef
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
import app.epistola.suite.mediator.query
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
 * A catalog in a UI route, optionally one of its versions: `acme/letters` for its next version,
 * `acme/letters@<version id>` for another draft of it (contract ADR 0002), or `acme/letters@1.2.0`
 * for a release, read-only (ADR 0033). A draft has no number until it is released, so its id
 * (contract `CatalogVersionId`) names it, and an id never has a dot, so it is never a number.
 * [draft] holds what follows the `@`, either.
 *
 * The draft travels in the catalog's own path segment (or `catalog` parameter) rather than in the
 * session, so a link to a draft is shareable, and every link, HTMX request and editor call built from
 * the segment stays on that draft. Without one the route works on the catalog's current version: its
 * next version. `@` cannot occur in a catalog key, so the two never collide.
 *
 * A catalog's [namespace] goes before its key, `acme/letters` (ADR 0034, section 9): within a tenant a
 * catalog is unique by namespace and key, so its pages are addressed `/tenants/{t}/catalogs/{namespace}/{key}/…`
 * ([catalogScopedRoutes]). A segment parsed from a key alone has none until the key is resolved.
 */
data class CatalogSegment(val key: CatalogKey, val draft: String? = null, val namespace: String? = null) {
    /** The segment as it appears in a URL: `[namespace/]key[@draft]`. */
    val value: String get() = (namespace?.let { "$it/" } ?: "") + (draft?.let { "${key.value}@$it" } ?: key.value)

    /**
     * The version this names, for a message's `catalogVersion`: a draft by its id, or a release by
     * its number (ADR 0033, read-only); null for the catalog's current version.
     */
    fun ref(tenantKey: TenantKey): CatalogReleaseRef? = draft?.let { CatalogReleaseRef(catalogIn(tenantKey), requireNotNull(DraftRef.parse(it)) { "Not a version: $it" }) }

    /** Whether this names a released version (`acme/letters@1.2.0`), which is read-only (ADR 0033). */
    val released: Boolean get() = draft?.let { DraftRef.parse(it) is DraftRef.Released } ?: false

    /** The catalog this names in [tenantKey], by its address; its [namespace] must be known. */
    fun catalogIn(tenantKey: TenantKey): CatalogId = CatalogId(requireNotNull(namespace) { "The catalog ${key.value} is addressed without its namespace" }, key, TenantId(tenantKey))

    /** [catalogIn] for a [TenantId]. */
    fun catalogIn(tenantId: TenantId): CatalogId = catalogIn(tenantId.key)

    /** The catalog's address, `namespace/key`, without a draft; the key alone when the namespace is not known. */
    val address: String get() = (namespace?.let { "$it/" } ?: "") + key.value

    /** The same catalog at draft [draft], or at its current version when that is null. */
    fun at(draft: String?): CatalogSegment = copy(draft = draft)

    /**
     * The segment to link a resource of catalog [catalog] (an address, `namespace/key`, or a
     * [CatalogId]) with: this one when it is the same catalog, so a list filtered to a draft links its
     * rows into the draft, else that catalog's address.
     */
    fun linkFor(catalog: Any?): String {
        val other = when (catalog) {
            null -> return value
            is CatalogId -> catalog.address
            else -> catalog.toString()
        }
        return if (other == address || other == key.value) value else other
    }

    override fun toString(): String = value

    companion object {
        /**
         * Parses `[namespace/]key[@<version>]`, the version a draft's id or a release's number
         * (`acme/letters@1.2.0`, ADR 0033). Throws [IllegalArgumentException] for an invalid key or
         * namespace, and [CatalogReleaseNotFoundException] for a version part that is neither: a link
         * naming no version there is a version that is not there, not a bad request.
         */
        fun parse(raw: String): CatalogSegment {
            val slash = raw.indexOf('/')
            if (slash >= 0) {
                val namespace = raw.substring(0, slash)
                require(CatalogNamespaces.isValid(namespace)) { "Invalid catalog namespace: $namespace" }
                return parse(raw.substring(slash + 1)).copy(namespace = namespace)
            }
            val at = raw.indexOf('@')
            if (at < 0) return CatalogSegment(CatalogKey.of(raw))
            val key = CatalogKey.of(raw.substring(0, at))
            val draft = raw.substring(at + 1).trim()
            if (draft == DraftRef.NEXT || DraftRef.parse(draft) == null) throw CatalogReleaseNotFoundException(key, draft)
            return CatalogSegment(key, draft)
        }

        fun parseOrNull(raw: String?): CatalogSegment? = raw?.takeIf { it.isNotBlank() }?.let { runCatching { parse(it.trim()) }.getOrNull() }
    }
}

/**
 * The catalog in the route: the `catalogId` path variable, and the draft it names if any, with the
 * namespace the address names before it (`{catalogNamespace}`, [catalogScopedRoutes]), or, for an
 * action reached by key alone, the namespace that key was resolved to ([CATALOG_NAMESPACE_ATTRIBUTE]).
 */
fun ServerRequest.catalogSegment(): CatalogSegment = CatalogSegment.parse(pathVariable("catalogId")).let { segment ->
    segment.copy(namespace = pathVariables()[CATALOG_NAMESPACE_VARIABLE] ?: attribute(CATALOG_NAMESPACE_ATTRIBUTE).orElse(null) as String? ?: segment.namespace)
}

/** The catalog in the route, by its address (ADR 0034, section 9). */
fun ServerRequest.catalog(tenantId: TenantId): CatalogId = catalogSegment().catalogIn(tenantId)

/** [catalog] for a [TenantKey]. */
fun ServerRequest.catalog(tenantKey: TenantKey): CatalogId = catalogSegment().catalogIn(tenantKey)

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
} catch (e: CatalogReleaseNotFoundException) {
    e.catalogKey
}

/**
 * The draft the `catalogId` path variable names (`letters@<draft id>`), for a message's
 * `catalogVersion`; null when it names none, which means the catalog's next version.
 */
fun ServerRequest.catalogVersion(): CatalogReleaseRef? = catalogSegment().ref(tenantId().key)

/**
 * The catalog a request is in: the `catalogId` path variable of a page or action under
 * `/catalogs/{catalogId}/…`, else the `catalog` filter or form parameter of a tenant-level partial
 * (an editor's picker search). Either may name a draft as `letters@<draft id>`; null when neither is
 * there or it is blank.
 */
fun ServerRequest.catalogParam(name: String = "catalog"): CatalogSegment? = pathVariables()["catalogId"]?.let { catalogSegment() }
    ?: namedCatalogParam(name)

/**
 * The catalog [ref] names in [tenantId]: `namespace/key` by its address, or a key alone, the tenant's
 * one catalog with that key (ADR 0034, section 9); null when it names none.
 */
fun catalogNamed(tenantId: TenantId, ref: String): CatalogId? {
    val value = ref.trim()
    val namespace = value.substringBeforeLast('/', "")
    val key = CatalogKey.validateOrNull(value.substringAfterLast('/')) ?: return null
    if (namespace.isNotEmpty()) return CatalogId(namespace, key, tenantId)
    return app.epistola.suite.catalog.queries.FindCatalogsByKey(tenantId.key, key).query().only
}

/**
 * The catalog the form or query parameter [name] names, `[namespace/]key[@draft]`: by its address, or,
 * by key alone, the tenant's one catalog with that key (ADR 0034, section 9). Null when the parameter
 * is absent or blank; a key that names no catalog, or several, gives a segment without a namespace.
 */
fun ServerRequest.namedCatalogParam(name: String): CatalogSegment? = param(name).orElse(null)?.ifBlank { null }?.let { CatalogSegment.parse(it.trim()).resolvedIn(tenantId().key) }

/**
 * This segment with its namespace: as written, or, for a key alone, the namespace of [tenantKey]'s one
 * catalog with that key (ADR 0034, section 9); still without one when the key names none, or several.
 */
fun CatalogSegment.resolvedIn(tenantKey: TenantKey): CatalogSegment = if (namespace != null) {
    this
} else {
    copy(namespace = app.epistola.suite.catalog.queries.FindCatalogsByKey(tenantKey, key).query().only?.namespace)
}

/**
 * Extract and validate a [TemplateId] from the `id` and `catalogId` path variables.
 * Returns null if the template key is invalid.
 */
fun ServerRequest.templateId(tenantId: TenantId): TemplateId? {
    val catalog = catalog(tenantId)
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
    val catalog = catalog(tenantId)
    val key = ThemeKey.validateOrNull(pathVariable("themeId")) ?: return null
    return ThemeId(key, catalog)
}

/**
 * Extract and validate a [StencilId] from the `stencilId` and `catalogId` path variables.
 * Returns null if the stencil key is invalid.
 */
fun ServerRequest.stencilId(tenantId: TenantId): StencilId? {
    val catalog = catalog(tenantId)
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
    val catalog = catalog(tenantId)
    val key = AttributeKey.validateOrNull(pathVariable("attributeId")) ?: return null
    return AttributeId(key, catalog)
}

/**
 * Extract and validate a [CodeListId] from the `codeListId` and `catalogId` path variables.
 * Returns null if the slug is invalid.
 */
fun ServerRequest.codeListId(tenantId: TenantId): CodeListId? {
    val catalog = catalog(tenantId)
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

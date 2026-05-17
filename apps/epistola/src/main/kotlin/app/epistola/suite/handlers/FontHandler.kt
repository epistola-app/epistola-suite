package app.epistola.suite.fonts

import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontId
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.model.Font
import app.epistola.suite.fonts.model.FontVariant
import app.epistola.suite.fonts.queries.GetFontVariantContent
import app.epistola.suite.fonts.queries.GetFontVariants
import app.epistola.suite.fonts.queries.ListFonts
import app.epistola.suite.mediator.query
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * UI handler for the backend-driven font picker (mirrors [AssetHandler]).
 *
 * Internal use only — these are NON-`/api` UI endpoints consumed by the
 * Lit editor/theme-editor. They MUST never be reached by external systems;
 * the REST API has its own (separate) surface.
 *
 * - `search`  → JSON list of the fonts visible to the editing context (the
 *   owning catalog + the always-present `system` catalog), each carrying the
 *   `@font-face` family name and per-variant content URLs the editor needs.
 * - `content` → streams a single font-face TTF with immutable cache headers.
 */
@Component
class FontHandler {

    fun search(request: ServerRequest): ServerResponse {
        val tenantKey = TenantKey.of(request.pathVariable("tenantId"))
        val tenantId = TenantId(tenantKey)
        val catalogFilter = request.param("catalog").orElse(null)?.ifBlank { null }?.let { CatalogKey.of(it) }

        // Fonts visible to the editing context = the owning catalog + the
        // system catalog. Query both, then merge with `system` last and
        // dedupe by (catalogKey, slug) so an owning-catalog override of a
        // system slug wins.
        val owningFonts = if (catalogFilter != null && catalogFilter != SYSTEM_CATALOG_KEY) {
            ListFonts(tenantId = tenantId, catalogKey = catalogFilter).query()
        } else {
            emptyList()
        }
        val systemFonts = ListFonts(tenantId = tenantId, catalogKey = SYSTEM_CATALOG_KEY).query()

        val merged = LinkedHashMap<Pair<String, String>, Font>()
        for (font in owningFonts + systemFonts) {
            merged.putIfAbsent(font.catalogKey.value to font.slug.value, font)
        }

        val fontInfoList = merged.values.map { font ->
            val variants = GetFontVariants(
                fontId = FontId(font.slug, CatalogId(font.catalogKey, tenantId)),
            ).query().map { it.variant.wire }

            val family = "epistola-${font.catalogKey.value}-${font.slug.value}"
            mapOf(
                "slug" to font.slug.value,
                "name" to font.name,
                "kind" to font.kind.wire,
                "catalogKey" to font.catalogKey.value,
                "variants" to variants,
                "css" to mapOf(
                    "family" to family,
                    "urls" to variants.associateWith { variant ->
                        "/tenants/${tenantKey.value}/fonts/${font.catalogKey.value}/${font.slug.value}/$variant/content"
                    },
                ),
            )
        }

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(fontInfoList)
    }

    fun content(request: ServerRequest): ServerResponse {
        val tenantKey = TenantKey.of(request.pathVariable("tenantId"))
        val catalogKey = CatalogKey.of(request.pathVariable("catalogId"))
        val slug = FontKey.of(request.pathVariable("slug"))
        val variant = try {
            FontVariant.fromWire(request.pathVariable("variant"))
        } catch (_: IllegalArgumentException) {
            return ServerResponse.notFound().build()
        }

        val bytes = GetFontVariantContent(
            tenantId = tenantKey,
            catalogKey = catalogKey,
            slug = slug,
            variant = variant,
        ).query() ?: return ServerResponse.notFound().build()

        return ServerResponse.ok()
            .contentType(MediaType.parseMediaType("font/ttf"))
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
            .body(bytes)
    }
}

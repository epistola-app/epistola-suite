package app.epistola.suite.api.v1

import app.epistola.suite.api.v1.shared.FontDto
import app.epistola.suite.api.v1.shared.FontListResponse
import app.epistola.suite.api.v1.shared.FontVariantDto
import app.epistola.suite.api.v1.shared.toDto
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontId
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.queries.GetFontVariants
import app.epistola.suite.fonts.queries.ListFonts
import app.epistola.suite.mediator.query
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Read-only REST surface for tenant + catalog font families.
 *
 * **Deliberately read-only — list + get only.** This mirrors the asset
 * precedent: font-face binaries (like image assets) are managed through the UI
 * and catalog exchange, never created/updated/deleted over the REST API. The
 * external `epistola-contract` artifact has no Fonts API, so this is a
 * hand-written controller (not a generated interface impl) — but it follows the
 * same `/api/tenants/{tenantId}/catalogs/{catalogId}/...` shape, content type,
 * and per-tenant `X-API-Key` auth as the generated controllers.
 *
 * Thin pass-through to the existing `ListFonts` / `GetFontVariants` queries —
 * no domain logic in the controller.
 */
@RestController
@RequestMapping("/api")
class EpistolaFontApi {

    @GetMapping(
        "/tenants/{tenantId}/catalogs/{catalogId}/fonts",
        produces = ["application/vnd.epistola.v1+json"],
    )
    fun listFonts(
        @PathVariable tenantId: String,
        @PathVariable catalogId: String,
    ): ResponseEntity<FontListResponse> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val catalogKey = CatalogKey.of(catalogId)
        val fonts = ListFonts(tenantId = tenantIdComposite, catalogKey = catalogKey).query()
        val items = fonts.map { font ->
            val variants = GetFontVariants(
                fontId = FontId(font.slug, CatalogId(catalogKey, tenantIdComposite)),
            ).query().map { FontVariantDto(it.weight, it.italic) }
            font.toDto(variants)
        }
        return ResponseEntity.ok(FontListResponse(items = items))
    }

    @GetMapping(
        "/tenants/{tenantId}/catalogs/{catalogId}/fonts/{fontSlug}",
        produces = ["application/vnd.epistola.v1+json"],
    )
    fun getFont(
        @PathVariable tenantId: String,
        @PathVariable catalogId: String,
        @PathVariable fontSlug: String,
    ): ResponseEntity<FontDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val catalogKey = CatalogKey.of(catalogId)
        val slug = FontKey.of(fontSlug)
        val font = ListFonts(tenantId = tenantIdComposite, catalogKey = catalogKey).query()
            .firstOrNull { it.slug == slug }
            ?: return ResponseEntity.notFound().build()
        val variants = GetFontVariants(
            fontId = FontId(slug, CatalogId(catalogKey, tenantIdComposite)),
        ).query().map { FontVariantDto(it.weight, it.italic) }
        return ResponseEntity.ok(font.toDto(variants))
    }
}

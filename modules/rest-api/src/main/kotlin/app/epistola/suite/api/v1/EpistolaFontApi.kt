// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.FontsApi
import app.epistola.api.model.FontDto
import app.epistola.api.model.FontListResponse
import app.epistola.api.model.FontVariantDto
import app.epistola.suite.api.v1.shared.ListSorting
import app.epistola.suite.api.v1.shared.Pagination
import app.epistola.suite.api.v1.shared.toDto
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontId
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.FontNotFoundException
import app.epistola.suite.fonts.queries.GetFontVariants
import app.epistola.suite.fonts.queries.ListFonts
import app.epistola.suite.mediator.query
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Read-only REST surface for tenant + catalog font families.
 *
 * Implements the generated `FontsApi` interface from `epistola-contract`
 * (tag `Fonts`, ops `listFonts` / `getFont`) — the DTO shape is contract-owned,
 * exactly like every other v1 controller (e.g. [EpistolaCodeListApi]).
 *
 * **Deliberately read-only — list + get only.** This mirrors the asset
 * precedent: font-face binaries (like image assets) are managed through the UI
 * and catalog exchange, never created/updated/deleted over the REST API. The
 * contract therefore exposes no font write operations.
 *
 * Thin pass-through to the existing `ListFonts` / `GetFontVariants` queries —
 * no domain logic in the controller.
 */
@RestController
@RequestMapping("/api")
class EpistolaFontApi : FontsApi {

    override fun listFonts(
        tenantId: String,
        catalogId: String,
        page: Int,
        size: Int,
        sort: String?,
        direction: String,
    ): ResponseEntity<FontListResponse> {
        // This endpoint has no sortable columns; reject a caller-supplied sort rather than ignore it.
        ListSorting.rejectUnsupportedSort(sort, direction)
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val catalogKey = CatalogKey.of(catalogId)
        val fonts = ListFonts(tenantId = tenantIdComposite, catalogKey = catalogKey).query()
        val slice = Pagination.paginate(fonts, page, size)
        val items = slice.items.map { font ->
            val variants = GetFontVariants(
                fontId = FontId(font.slug, CatalogId(catalogKey, tenantIdComposite)),
            ).query().map { FontVariantDto(it.weight, it.italic) }
            font.toDto(variants)
        }
        return ResponseEntity.ok(FontListResponse(items = items, page = slice.page))
    }

    override fun getFont(
        tenantId: String,
        catalogId: String,
        fontSlug: String,
    ): ResponseEntity<FontDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val catalogKey = CatalogKey.of(catalogId)
        val slug = FontKey.of(fontSlug)
        val font = ListFonts(tenantId = tenantIdComposite, catalogKey = catalogKey).query()
            .firstOrNull { it.slug == slug }
            ?: throw FontNotFoundException(tenantIdComposite.key, catalogKey, slug)
        val variants = GetFontVariants(
            fontId = FontId(slug, CatalogId(catalogKey, tenantIdComposite)),
        ).query().map { FontVariantDto(it.weight, it.italic) }
        return ResponseEntity.ok(font.toDto(variants))
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.FontsApi
import app.epistola.api.model.FontDto
import app.epistola.api.model.FontListResponse
import app.epistola.api.model.FontVariantDto
import app.epistola.catalog.protocol.FontResource
import app.epistola.suite.api.v1.shared.ListSorting
import app.epistola.suite.api.v1.shared.Pagination
import app.epistola.suite.api.v1.shared.catalogId
import app.epistola.suite.api.v1.shared.catalogVersionAt
import app.epistola.suite.api.v1.shared.releasedResource
import app.epistola.suite.api.v1.shared.releasedResources
import app.epistola.suite.api.v1.shared.toDto
import app.epistola.suite.catalog.versions.ResolvedCatalogVersion
import app.epistola.suite.common.ids.FontId
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.fonts.FontNotFoundException
import app.epistola.suite.fonts.model.Font
import app.epistola.suite.fonts.queries.GetFontVariants
import app.epistola.suite.fonts.queries.ListFonts
import app.epistola.suite.mediator.query
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Read-only REST surface for a catalog version's font families.
 *
 * **Deliberately read-only — list + get only.** This mirrors the asset precedent: font-face
 * binaries (like image assets) are managed through the UI and catalog exchange, never
 * created/updated/deleted over the REST API. The contract therefore exposes no font write operations.
 *
 * A draft reads the working copy (`ListFonts` / `GetFontVariants`); a released version reads the
 * fonts the release holds.
 */
@RestController
@RequestMapping("/api")
class EpistolaFontApi : FontsApi {

    override fun listFonts(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        page: Int,
        size: Int,
        sort: String?,
        direction: String,
    ): ResponseEntity<FontListResponse> {
        // This endpoint has no sortable columns; reject a caller-supplied sort rather than ignore it.
        ListSorting.rejectUnsupportedSort(sort, direction)
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        if (!at.isDraft) {
            // Released content is paged in memory: it is assembled whole, and bounded by the catalog.
            val slice = Pagination.pageOfRelease(at.releasedResources<FontResource>("font"), page, size)
            return ResponseEntity.ok(FontListResponse(items = slice.items.map { it.toDto(at) }, page = slice.page))
        }
        val fonts = ListFonts(tenantId = TenantId(at.catalog.tenantKey), catalogKey = at.catalogKey).query()
        val slice = Pagination.paginate(fonts, page, size)
        return ResponseEntity.ok(FontListResponse(items = slice.items.map { it.toDto(at) }, page = slice.page))
    }

    override fun getFont(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        fontKey: String,
    ): ResponseEntity<FontDto> {
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val key = FontKey.of(fontKey)
        val font = if (at.isDraft) {
            ListFonts(tenantId = TenantId(at.catalog.tenantKey), catalogKey = at.catalogKey).query()
                .firstOrNull { it.slug == key }
                ?.toDto(at)
        } else {
            at.releasedResource<FontResource>("font", fontKey)?.toDto(at)
        } ?: throw FontNotFoundException(at.catalog.tenantKey, at.catalogKey, key)
        return ResponseEntity.ok(font)
    }

    private fun Font.toDto(at: ResolvedCatalogVersion): FontDto {
        val variants = GetFontVariants(fontId = FontId(slug, at.catalogId)).query().map { FontVariantDto(it.weight, it.italic) }
        return toDto(at.catalog, variants)
    }
}

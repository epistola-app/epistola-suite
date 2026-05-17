package app.epistola.suite.fonts

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.UnsupportedAssetTypeException
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontId
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.commands.DeleteFont
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.fonts.commands.ImportFontVariant
import app.epistola.suite.fonts.model.Font
import app.epistola.suite.fonts.model.FontInUseException
import app.epistola.suite.fonts.model.FontKind
import app.epistola.suite.fonts.model.FontVariantSource
import app.epistola.suite.fonts.queries.GetFontVariantContent
import app.epistola.suite.fonts.queries.GetFontVariants
import app.epistola.suite.fonts.queries.ListFonts
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.isHtmx
import app.epistola.suite.htmx.page
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.queries.GetTenant
import jakarta.servlet.http.Part
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
 *   `@font-face` family name and one content URL per weight/italic face.
 * - `content` → streams a single font-face TTF with immutable cache headers.
 */
@Component
class FontHandler {

    /**
     * Font management list page (mirrors `AssetHandler.list`). Lists font
     * families for the tenant (optionally filtered by catalog), each annotated
     * with its weight/italic faces and whether its catalog is editable
     * (AUTHORED) or read-only (SUBSCRIBED, e.g. `system`).
     */
    fun list(request: ServerRequest): ServerResponse {
        val tenantKey = TenantKey.of(request.pathVariable("tenantId"))
        val tenantId = TenantId(tenantKey)
        val catalogFilter = request.param("catalog").orElse(null)?.ifBlank { null }?.let { CatalogKey.of(it) }
        val tenant = GetTenant(id = tenantKey).query()
        val catalogs = ListCatalogs(tenantKey).query()
        val fonts = ListFonts(tenantId = tenantId, catalogKey = catalogFilter).query()
        return ServerResponse.ok().render(
            "layout/shell",
            mapOf(
                "contentView" to "fonts/list",
                "pageTitle" to "Fonts - Epistola",
                "tenantId" to tenantKey.value,
                "tenant" to tenant,
                "catalogs" to catalogs,
                "selectedCatalog" to (catalogFilter?.value ?: ""),
                "fonts" to fonts.map { toFontView(tenantId, it) },
                "activeNavSection" to "fonts",
            ),
        )
    }

    /**
     * Upload form (mirrors `AssetHandler.newForm`). Only AUTHORED catalogs are
     * offered as upload targets — SUBSCRIBED catalogs reject writes.
     */
    fun newForm(request: ServerRequest): ServerResponse {
        val tenantKey = TenantKey.of(request.pathVariable("tenantId"))
        val catalogs = ListCatalogs(tenantKey).query().filter { it.type == CatalogType.AUTHORED }
        return ServerResponse.ok().page("fonts/new") {
            "pageTitle" to "Upload Font - Epistola"
            "tenantId" to tenantKey.value
            "catalogs" to catalogs
        }
    }

    /**
     * Upload handler: creates an `assets` row per provided face, then upserts
     * the family + variants via [ImportFont]. The form submits a repeating set
     * of faces — parallel `file` / `weight` / `italic` fields, one entry per
     * row. At least one face file is required. AUTHORED catalogs only.
     */
    fun upload(request: ServerRequest): ServerResponse {
        val tenantKey = TenantKey.of(request.pathVariable("tenantId"))
        val tenantId = TenantId(tenantKey)
        val multipartData = request.multipartData()

        fun field(name: String): String? = multipartData[name]?.firstOrNull()
            ?.let { String(it.inputStream.readAllBytes()).trim() }?.ifBlank { null }

        fun badRequest(message: String?): ServerResponse = ServerResponse.badRequest()
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("error" to (message ?: "Invalid request")))

        val slugStr = field("slug") ?: return badRequest("slug is required")
        val slug = try {
            FontKey.of(slugStr)
        } catch (e: IllegalArgumentException) {
            return badRequest(e.message)
        }
        val name = field("name") ?: return badRequest("name is required")
        val kind = try {
            FontKind.fromWire(field("kind") ?: "")
        } catch (e: IllegalArgumentException) {
            return badRequest(e.message)
        }
        val catalogKeyStr = field("catalog") ?: return badRequest("catalog is required")
        val catalogKey = CatalogKey.of(catalogKeyStr)

        // Collect the repeating face rows: parallel `file` / `weight` /
        // `italic` fields, indexed positionally. Rows whose file is empty are
        // skipped (the template renders at least one row but extras may be
        // blank). At least one face file is required.
        val fileParts: List<Part> = multipartData["file"].orEmpty()
        val weights: List<String> = multipartData["weight"].orEmpty()
            .map { String(it.inputStream.readAllBytes()).trim() }
        val italics: List<String> = multipartData["italic"].orEmpty()
            .map { String(it.inputStream.readAllBytes()).trim() }

        data class FaceRow(val part: Part, val weight: Int, val italic: Boolean)
        val rows = mutableListOf<FaceRow>()
        for ((idx, part) in fileParts.withIndex()) {
            if ((part.submittedFileName ?: "").isBlank() || part.size <= 0L) continue
            val weight = weights.getOrNull(idx)?.toIntOrNull()
                ?: return badRequest("Each face needs a numeric weight (1–1000)")
            if (weight !in 1..1000) {
                return badRequest("Font weight must be between 1 and 1000")
            }
            val italic = italics.getOrNull(idx)?.equals("true", ignoreCase = true) == true
            rows += FaceRow(part, weight, italic)
        }

        if (rows.isEmpty()) {
            return badRequest("At least one face file is required")
        }
        if (rows.map { it.weight to it.italic }.toSet().size != rows.size) {
            return badRequest("Each (weight, italic) face must be unique")
        }

        val importVariants = mutableListOf<ImportFontVariant>()
        try {
            for (row in rows) {
                val part = row.part
                val contentType = part.contentType
                    ?: return badRequest("No content type on uploaded face")
                val mediaType = try {
                    AssetMediaType.fromMimeType(contentType)
                } catch (e: UnsupportedAssetTypeException) {
                    return badRequest(e.message)
                }
                if (mediaType !in FONT_MEDIA_TYPES) {
                    return badRequest("Unsupported font format: $contentType. Use TTF or OTF.")
                }
                val bytes = part.inputStream.use { it.readAllBytes() }
                app.epistola.generation.pdf.FontBytesValidator.rejectionReason(bytes)?.let { reason ->
                    return badRequest("Face ${row.weight}${if (row.italic) " italic" else ""}: $reason")
                }
                val asset = UploadAsset(
                    tenantId = tenantKey,
                    name = part.submittedFileName ?: "${slug.value}-${row.weight}${if (row.italic) "i" else ""}",
                    mediaType = mediaType,
                    content = bytes,
                    width = null,
                    height = null,
                    catalogKey = catalogKey,
                ).execute()
                importVariants += ImportFontVariant(
                    weight = row.weight,
                    italic = row.italic,
                    source = FontVariantSource.ASSET,
                    assetKey = asset.id,
                )
            }

            ImportFont(
                tenantId = tenantId,
                catalogKey = catalogKey,
                slug = slug.value,
                name = name,
                kind = kind.wire,
                variants = importVariants,
            ).execute()
        } catch (e: CatalogReadOnlyException) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to e.message))
        }

        if (request.isHtmx) {
            return ServerResponse.ok()
                .header("HX-Redirect", "/tenants/${tenantKey.value}/fonts")
                .build()
        }
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("slug" to slug.value, "catalogKey" to catalogKey.value))
    }

    /**
     * Delete a font family + its uploaded binaries (AUTHORED only). Surfaces
     * [FontInUseException] the same way `AssetHandler.delete` surfaces
     * `AssetInUseException` — a JSON 400 the confirm dialog renders inline.
     */
    fun delete(request: ServerRequest): ServerResponse {
        val tenantKey = TenantKey.of(request.pathVariable("tenantId"))
        val tenantId = TenantId(tenantKey)
        val catalogKey = CatalogKey.of(request.pathVariable("catalogId"))
        val slug = FontKey.of(request.pathVariable("slug"))
        val force = request.param("force").orElse("false").toBoolean()

        try {
            DeleteFont(
                fontId = FontId(slug, CatalogId(catalogKey, tenantId)),
                force = force,
            ).execute()
        } catch (e: FontInUseException) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to e.message))
        } catch (e: CatalogReadOnlyException) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to e.message))
        }

        val tenant = GetTenant(id = tenantKey).query()
        val fonts = ListFonts(tenantId = tenantId).query()
        return request.htmx {
            fragment("fonts/list", "font-grid-items") {
                "tenantId" to tenantKey.value
                "tenant" to tenant
                "fonts" to fonts.map { toFontView(tenantId, it) }
            }
            onNonHtmx { redirect("/tenants/${tenantKey.value}/fonts") }
        }
    }

    /** List-row view model: family metadata + its weight/italic faces. */
    private fun toFontView(tenantId: TenantId, font: Font): Map<String, Any?> {
        val faces = GetFontVariants(
            fontId = FontId(font.slug, CatalogId(font.catalogKey, tenantId)),
        ).query().map { row ->
            mapOf(
                "weight" to row.weight,
                "italic" to row.italic,
                "label" to faceLabel(row.weight, row.italic),
            )
        }
        return mapOf(
            "slug" to font.slug.value,
            "name" to font.name,
            "kind" to font.kind.wire,
            "catalogKey" to font.catalogKey.value,
            "catalogType" to (font.catalogType?.name ?: CatalogType.AUTHORED.name),
            "variants" to faces,
        )
    }

    /** Human-friendly chip label, e.g. `400`, `700 italic`. */
    private fun faceLabel(weight: Int, italic: Boolean): String = if (italic) "$weight italic" else "$weight"

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
            val rows = GetFontVariants(
                fontId = FontId(font.slug, CatalogId(font.catalogKey, tenantId)),
            ).query()

            val family = "epistola-${font.catalogKey.value}-${font.slug.value}"
            mapOf(
                "slug" to font.slug.value,
                "name" to font.name,
                "kind" to font.kind.wire,
                "catalogKey" to font.catalogKey.value,
                "variants" to rows.map { mapOf("weight" to it.weight, "italic" to it.italic) },
                "css" to mapOf(
                    "family" to family,
                    "faces" to rows.map { row ->
                        mapOf(
                            "weight" to row.weight,
                            "italic" to row.italic,
                            "url" to "/tenants/${tenantKey.value}/fonts/${font.catalogKey.value}/" +
                                "${font.slug.value}/${row.weight}/${row.italic}/content",
                        )
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
        val weight = request.pathVariable("weight").toIntOrNull()
            ?.takeIf { it in 1..1000 }
            ?: return ServerResponse.notFound().build()
        val italic = request.pathVariable("italic").toBooleanStrictOrNull()
            ?: return ServerResponse.notFound().build()

        val bytes = GetFontVariantContent(
            tenantId = tenantKey,
            catalogKey = catalogKey,
            slug = slug,
            weight = weight,
            italic = italic,
        ).query() ?: return ServerResponse.notFound().build()

        return ServerResponse.ok()
            .contentType(MediaType.parseMediaType("font/ttf"))
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
            .body(bytes)
    }

    private companion object {
        /** Asset media types accepted for font-face binaries. */
        val FONT_MEDIA_TYPES = setOf(
            AssetMediaType.TTF,
            AssetMediaType.OTF,
        )
    }
}

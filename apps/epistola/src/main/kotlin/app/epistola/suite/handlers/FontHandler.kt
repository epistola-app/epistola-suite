package app.epistola.suite.fonts

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.UnsupportedAssetTypeException
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.Catalog
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
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.isHtmx
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
class FontHandler(
    private val assetTypeCatalog: app.epistola.suite.assets.AssetTypeCatalog,
) {

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
     * Upload dialog (mirrors `AssetHandler.newForm`). URL-addressable create
     * convention: an in-app trigger (hx-get) gets just the dialog fragment;
     * direct navigation / boost gets the host list page with the dialog embedded
     * and opened. Only AUTHORED catalogs are offered as upload targets —
     * SUBSCRIBED catalogs reject writes — under the distinct `authoredCatalogs`
     * key so it doesn't collide with the list's own `catalogs` filter var.
     */
    fun newForm(request: ServerRequest): ServerResponse {
        val tenantKey = TenantKey.of(request.pathVariable("tenantId"))
        // One ListCatalogs whichever branch renders (fragment models are lazy):
        // the list filter uses all catalogs, the dialog's <select> the authored subset.
        val allCatalogs by lazy { ListCatalogs(tenantKey).query() }
        val authoredCatalogs by lazy { allCatalogs.filter { it.type == CatalogType.AUTHORED } }
        return request.htmx {
            fragment("fonts/new", "dialog") {
                "tenantId" to tenantKey.value
                "authoredCatalogs" to authoredCatalogs
            }
            onNonHtmx {
                page("fonts/list") {
                    fontPageModel(tenantKey, allCatalogs)
                    "openDialog" to true
                    "authoredCatalogs" to authoredCatalogs
                }
            }
        }
    }

    /** The full-page list model (shared by the newForm / upload non-HTMX branches). */
    private fun ModelBuilder.fontPageModel(tenantKey: TenantKey, catalogs: List<Catalog>) {
        val tenantId = TenantId(tenantKey)
        "pageTitle" to "Fonts - Epistola"
        "tenantId" to tenantKey.value
        "tenant" to GetTenant(id = tenantKey).query()
        "catalogs" to catalogs
        "selectedCatalog" to ""
        "fonts" to ListFonts(tenantId = tenantId).query().map { toFontView(tenantId, it) }
        "activeNavSection" to "fonts"
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

        // Accumulate field → message (the OOB per-field error track) instead of
        // first-error-wins, so the dialog can surface every problem at once. The
        // repeating faces fold into ONE aggregate `faces` key (per-row errors are
        // out of scope), mirroring code-list's `errors.entries`.
        val errors = LinkedHashMap<String, String>()

        val slugStr = field("slug")
        val slug: FontKey? = when {
            slugStr == null -> {
                errors["slug"] = "Slug is required"
                null
            }
            else -> try {
                FontKey.of(slugStr)
            } catch (e: IllegalArgumentException) {
                errors["slug"] = e.message ?: "Invalid slug"
                null
            }
        }
        val name = field("name")
        if (name == null) errors["name"] = "Display name is required"
        val kindStr = field("kind")
        val kind: FontKind? = when {
            kindStr == null -> {
                errors["kind"] = "Kind is required"
                null
            }
            else -> try {
                FontKind.fromWire(kindStr)
            } catch (e: IllegalArgumentException) {
                errors["kind"] = e.message ?: "Invalid kind"
                null
            }
        }
        val catalogStr = field("catalog")
        val catalogKey: CatalogKey? = when {
            catalogStr == null -> {
                errors["catalog"] = "Catalog is required"
                null
            }

            else -> CatalogKey.validateOrNull(catalogStr).also {
                if (it == null) errors["catalog"] = "Invalid catalog ID format"
            }
        }

        // Read each provided face ONCE (Part streams are one-shot), then validate
        // ALL of them before persisting anything — so a bad face reports an error
        // rather than leaving partially-created asset rows behind. Rows whose file
        // is empty are skipped (the form renders one row; extras may be blank).
        val fileParts: List<Part> = multipartData["file"].orEmpty()
        val weights: List<String> = multipartData["weight"].orEmpty()
            .map { String(it.inputStream.readAllBytes()).trim() }
        val italics: List<String> = multipartData["italic"].orEmpty()
            .map { String(it.inputStream.readAllBytes()).trim() }

        data class FaceRow(
            val weight: Int,
            val italic: Boolean,
            val filename: String,
            val contentType: String?,
            val bytes: ByteArray,
        )
        val rows = mutableListOf<FaceRow>()
        var faceError: String? = null
        for ((idx, part) in fileParts.withIndex()) {
            if ((part.submittedFileName ?: "").isBlank() || part.size <= 0L) continue
            val weight = weights.getOrNull(idx)?.toIntOrNull()
            if (weight == null) {
                faceError = "Each face needs a numeric weight (1–1000)"
                break
            }
            if (weight !in 1..1000) {
                faceError = "Font weight must be between 1 and 1000"
                break
            }
            val italic = italics.getOrNull(idx)?.equals("true", ignoreCase = true) == true
            val bytes = part.inputStream.use { it.readAllBytes() }
            rows += FaceRow(
                weight = weight,
                italic = italic,
                filename = part.submittedFileName
                    ?: "${slug?.value ?: "font"}-$weight${if (italic) "i" else ""}",
                contentType = part.contentType,
                bytes = bytes,
            )
        }
        if (faceError == null && rows.isEmpty()) {
            faceError = "At least one face file is required"
        }
        if (faceError == null && rows.map { it.weight to it.italic }.toSet().size != rows.size) {
            faceError = "Each (weight, italic) face must be unique"
        }
        if (faceError == null) {
            for (row in rows) {
                val contentType = row.contentType
                if (contentType == null) {
                    faceError = "No content type on uploaded face"
                    break
                }
                val mediaType = try {
                    assetTypeCatalog.require(contentType)
                } catch (e: UnsupportedAssetTypeException) {
                    faceError = e.message ?: "Unsupported font format"
                    break
                }
                if (mediaType !in FONT_MEDIA_TYPES) {
                    faceError = "Unsupported font format: $contentType. Use TTF or OTF."
                    break
                }
                val reason = app.epistola.generation.pdf.FontBytesValidator.rejectionReason(row.bytes)
                if (reason != null) {
                    faceError = "Face ${row.weight}${if (row.italic) " italic" else ""}: $reason"
                    break
                }
            }
        }
        if (faceError != null) errors["faces"] = faceError

        // Persist only when everything validated. UploadAsset/ImportFont can still
        // reject a read-only (SUBSCRIBED) catalog — fold that onto the catalog field.
        if (errors.isEmpty()) {
            try {
                val importVariants = rows.map { row ->
                    val asset = UploadAsset(
                        tenantId = tenantKey,
                        name = row.filename,
                        mediaType = assetTypeCatalog.require(row.contentType!!),
                        content = row.bytes,
                        width = null,
                        height = null,
                        catalogKey = catalogKey!!,
                    ).execute()
                    ImportFontVariant(
                        weight = row.weight,
                        italic = row.italic,
                        source = FontVariantSource.ASSET,
                        assetKey = asset.id,
                    )
                }
                ImportFont(
                    tenantId = tenantId,
                    catalogKey = catalogKey!!,
                    slug = slug!!.value,
                    name = name!!,
                    kind = kind!!.wire,
                    variants = importVariants,
                ).execute()
            } catch (e: CatalogReadOnlyException) {
                errors["catalog"] = e.message ?: "Catalog is read-only"
            }
        }

        if (errors.isNotEmpty()) {
            // Browser dialog (HTMX): OOB-swap the per-field spans only — never
            // re-render the form body (file inputs + added face rows would be lost).
            if (request.isHtmx) {
                val authoredCatalogs by lazy {
                    ListCatalogs(tenantKey).query().filter { it.type == CatalogType.AUTHORED }
                }
                return request.htmx {
                    dialogFieldErrorsOob("fonts/new", "field-errors", errors)
                    // Boosted full-page submit: re-render the host page with the
                    // dialog open and the messages shown (files can't survive a
                    // full navigation regardless).
                    onNonHtmx {
                        page(422, "fonts/list") {
                            fontPageModel(tenantKey, ListCatalogs(tenantKey).query())
                            "openDialog" to true
                            "authoredCatalogs" to authoredCatalogs
                            "errors" to errors
                        }
                    }
                }
            }
            // Editor / non-HTMX JSON contract (unchanged): first error message.
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to errors.values.first()))
        }

        // Success. Browser dialog: close + OOB-refresh the grid; boosted: redirect.
        if (request.isHtmx) {
            val fonts = ListFonts(tenantId = tenantId).query().map { toFontView(tenantId, it) }
            return request.htmx {
                dialogSuccess("fonts/list", "font-grid-items", "/tenants/${tenantKey.value}/fonts") {
                    "tenantId" to tenantKey.value
                    "tenant" to GetTenant(id = tenantKey).query()
                    "fonts" to fonts
                }
                onNonHtmx { redirect("/tenants/${tenantKey.value}/fonts") }
            }
        }
        // Editor / non-HTMX JSON contract (unchanged).
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("slug" to slug!!.value, "catalogKey" to catalogKey!!.value))
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

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets

import app.epistola.suite.assets.commands.DeleteAsset
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.assets.queries.ListAssets
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.handlers.CatalogDraftBar
import app.epistola.suite.handlers.FromOtherCatalog
import app.epistola.suite.handlers.FromOtherCatalogs
import app.epistola.suite.htmx.CatalogSegment
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.catalogParam
import app.epistola.suite.htmx.catalogSegment
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.isHtmx
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.validation.ValidationException
import jakarta.servlet.http.Part
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import javax.imageio.ImageIO

@Component
class AssetHandler(
    private val assetTypeCatalog: app.epistola.suite.assets.AssetTypeCatalog,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * The Images UI lists every asset *except* fonts — font binaries are managed
     * on their own page ([app.epistola.suite.fonts.FontHandler]) as font families
     * with weight/italic faces. Everything else (images and other media) shows here.
     */
    private fun listImages(
        tenantId: TenantKey,
        searchTerm: String? = null,
        catalogKey: CatalogKey? = null,
        version: CatalogVersionRef? = null,
    ): List<Asset> = ListAssets(tenantId = tenantId, searchTerm = searchTerm, catalogKey = catalogKey, catalogVersion = version)
        .query()
        .filter { it.mediaType.category != AssetMediaCategory.FONT }

    fun list(request: ServerRequest): ServerResponse {
        val tenantId = TenantKey.of(request.pathVariable("tenantId"))
        // The filter may name a draft (`letters@2.0.0`); its images are then the draft's.
        val segment = request.catalogParam()
        val tenant = GetTenant(id = tenantId).query()
        val catalogs = ListCatalogs(tenantId).query()
        val assets = listImages(tenantId = tenantId, catalogKey = segment?.key, version = segment?.ref(tenantId))
        val bar = segment?.let { s -> CatalogDraftBar.of(tenantId, s) { "/tenants/${tenantId.value}/images?catalog=${it.value}" } }
        // What the catalogs this one depends on hold of this kind, listed read-only below its own.
        val fromOtherCatalogs = FromOtherCatalogs.of(bar, { listImages(tenantId = tenantId, catalogKey = it) }) { a, c, n -> FromOtherCatalog(a.name, "/tenants/${tenantId.value}/images?catalog=${c.value}", n) }
        return ServerResponse.ok().render(
            "layout/shell",
            mapOf(
                "contentView" to "images/list",
                "pageTitle" to "Images - Epistola",
                "tenantId" to tenantId.value,
                "tenant" to tenant,
                "catalogs" to catalogs,
                "selectedCatalog" to (segment?.value ?: ""),
                "draftSegment" to segment,
                "draftBar" to bar,
                "fromOtherCatalogs" to fromOtherCatalogs,
                "assets" to assets,
                "activeNavSection" to "images",
            ),
        )
    }

    fun search(request: ServerRequest): ServerResponse {
        val tenantId = TenantKey.of(request.pathVariable("tenantId"))
        val searchTerm = request.param("q").orElse(null)
        val segment = request.catalogParam()
        val catalogFilter = segment?.key
        val version = segment?.ref(tenantId)

        // HTMX search — return HTML fragment
        if (request.isHtmx) {
            val tenant = GetTenant(id = tenantId).query()
            val assets = listImages(tenantId = tenantId, searchTerm = searchTerm, catalogKey = catalogFilter, version = version)
            return request.htmx {
                fragment("images/list", "asset-grid-items") {
                    "tenantId" to tenantId.value
                    "tenant" to tenant
                    "assets" to assets
                    "selectedCatalog" to (segment?.value ?: "")
                    "draftSegment" to segment
                    // Lets the fragment tell "no matches" apart from "none yet". A search swaps only
                    // this fragment, so the distinction has to reach it.
                    "searchTerm" to searchTerm.orEmpty()
                }
                onNonHtmx { redirect("/tenants/${tenantId.value}/images") }
            }
        }

        // Editor calls with Accept: application/json
        val assets = listImages(tenantId = tenantId, searchTerm = searchTerm, catalogKey = catalogFilter, version = version)
        val assetInfoList = assets.map { asset ->
            mapOf(
                "id" to asset.id.value,
                "name" to asset.name,
                "mediaType" to asset.mediaType.mimeType,
                "sizeBytes" to asset.sizeBytes,
                "width" to asset.width,
                "height" to asset.height,
                "catalogKey" to asset.catalogKey.value,
                "contentUrl" to "/tenants/${tenantId.value}/images/${segment?.linkFor(asset.catalogKey.value) ?: asset.catalogKey.value}/${asset.id.value}/content",
            )
        }
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(assetInfoList)
    }

    /**
     * Catalogs the tenant can pick images from, as JSON for the editor asset
     * picker's catalog chooser. Same data source as the Images list dropdown
     * ([ListCatalogs]); a UI handler, never the REST API.
     */
    fun catalogs(request: ServerRequest): ServerResponse {
        val tenantId = TenantKey.of(request.pathVariable("tenantId"))
        val catalogList = ListCatalogs(tenantId).query().map { catalog ->
            mapOf(
                "key" to catalog.id.value,
                "name" to catalog.name,
                "type" to catalog.type.name,
            )
        }
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(catalogList)
    }

    /**
     * Upload dialog (mirrors `FontHandler.newForm`). URL-addressable create
     * convention: an in-app trigger (hx-get) gets just the dialog fragment;
     * direct navigation / boost gets the host list page with the dialog embedded
     * and opened. Only AUTHORED catalogs are offered as upload targets (under the
     * distinct `authoredCatalogs` key so it doesn't collide with the list's own
     * `catalogs` filter var).
     */
    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = TenantKey.of(request.pathVariable("tenantId"))
        val allCatalogs by lazy { ListCatalogs(tenantId).query() }
        val authoredCatalogs by lazy { allCatalogs.filter { it.type == CatalogType.AUTHORED } }
        return request.htmx {
            fragment("images/new", "dialog") {
                "tenantId" to tenantId.value
                // Opened from a list filtered to a draft, the dialog creates in that draft.
                // The catalog it was opened on, a draft or plain: a dialog opened from a catalog's page
                // creates there, rather than in whichever catalog sorts first.
                "draftSegment" to request.catalogParam()
                "authoredCatalogs" to authoredCatalogs
            }
            onNonHtmx {
                page("images/list") {
                    imagePageModel(tenantId, allCatalogs)
                    "openDialog" to true
                    "authoredCatalogs" to authoredCatalogs
                }
            }
        }
    }

    /** The full-page list model (shared by the newForm / upload non-HTMX branches). */
    private fun ModelBuilder.imagePageModel(tenantId: TenantKey, catalogs: List<Catalog>) {
        "pageTitle" to "Images - Epistola"
        "tenantId" to tenantId.value
        "tenant" to GetTenant(id = tenantId).query()
        "catalogs" to catalogs
        "selectedCatalog" to ""
        "assets" to listImages(tenantId = tenantId)
        "activeNavSection" to "images"
    }

    fun upload(request: ServerRequest): ServerResponse {
        val tenantId = TenantKey.of(request.pathVariable("tenantId"))
        val multipartData = request.multipartData()

        // Accumulate field → message (the OOB per-field error track). Two keys:
        // `catalog`, and `file` — every file-related failure (missing, no content
        // type, unsupported, too large) folds into the one `file` span.
        val errors = LinkedHashMap<String, String>()

        val catalogStr = multipartData["catalog"]?.firstOrNull()?.let {
            String(it.inputStream.readAllBytes()).trim()
        }?.ifBlank { null }
        // The catalog, or one of its drafts (`letters@2.0.0`) when uploading from inside one.
        val catalogSegment = when {
            catalogStr == null -> {
                errors["catalog"] = "Catalog is required"
                null
            }

            else -> CatalogSegment.parseOrNull(catalogStr).also {
                if (it == null) errors["catalog"] = "Invalid catalog ID format"
            }
        }
        val catalogKey = catalogSegment?.key

        val filePart: Part? = multipartData["file"]?.firstOrNull()
        var contentBytes: ByteArray? = null
        var originalFilename: String? = null
        var mediaType: AssetMediaType? = null
        if (filePart == null) {
            errors["file"] = "No file provided"
        } else {
            contentBytes = filePart.inputStream.use { it.readAllBytes() }
            originalFilename = filePart.submittedFileName ?: "unnamed"
            val contentType = filePart.contentType
            if (contentType == null) {
                errors["file"] = "No content type on uploaded file"
            } else {
                try {
                    mediaType = assetTypeCatalog.require(contentType)
                } catch (e: UnsupportedAssetTypeException) {
                    errors["file"] = e.message ?: "Unsupported asset media type"
                }
            }
        }

        // Extract image dimensions (null for SVG or on failure) — best-effort
        // metadata, never an error.
        val dimensions = if (mediaType != null && mediaType != AssetMediaType.SVG && contentBytes != null) {
            try {
                contentBytes.inputStream().use { stream ->
                    val image = ImageIO.read(stream)
                    if (image != null) image.width to image.height else null
                }
            } catch (e: Exception) {
                logger.warn("Failed to read image dimensions for {}: {}", originalFilename, e.message)
                null
            }
        } else {
            null
        }

        var asset: Asset? = null
        if (errors.isEmpty()) {
            try {
                asset = UploadAsset(
                    tenantId = tenantId,
                    name = originalFilename!!,
                    mediaType = mediaType!!,
                    content = contentBytes!!,
                    width = dimensions?.first,
                    height = dimensions?.second,
                    catalogKey = catalogKey!!,
                    catalogVersion = catalogSegment.ref(tenantId),
                ).execute()
            } catch (e: AssetTooLargeException) {
                errors["file"] = e.message ?: "That image is too large."
            } catch (e: CatalogReadOnlyException) {
                errors["catalog"] = e.message ?: "Catalog is read-only"
            } catch (e: ValidationException) {
                // Bad file name (markup/control chars #644, over-long #692) → file field, not a 500.
                errors["file"] = e.message
            }
        }

        if (errors.isNotEmpty()) {
            // Browser dialog (HTMX): OOB-swap the per-field spans only — never
            // re-render the form body (the chosen file would be lost).
            if (request.isHtmx) {
                val authoredCatalogs by lazy {
                    ListCatalogs(tenantId).query().filter { it.type == CatalogType.AUTHORED }
                }
                return request.htmx {
                    dialogFieldErrorsOob("images/new", "field-errors", errors)
                    onNonHtmx {
                        page(422, "images/list") {
                            imagePageModel(tenantId, ListCatalogs(tenantId).query())
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

        val created = asset!!

        // Success. Browser dialog: close + OOB-refresh the grid; boosted: redirect.
        if (request.isHtmx) {
            val draft = catalogSegment?.takeIf { it.version != null }
            val assets = listImages(tenantId = tenantId, catalogKey = draft?.key, version = draft?.ref(tenantId))
            return request.htmx {
                dialogSuccess("images/list", "asset-list", "/tenants/${tenantId.value}/images") {
                    "tenantId" to tenantId.value
                    "tenant" to GetTenant(id = tenantId).query()
                    "assets" to assets
                    "selectedCatalog" to (draft?.value ?: "")
                    "draftSegment" to draft
                }
                onNonHtmx { redirect("/tenants/${tenantId.value}/images") }
            }
        }

        // Editor API calls (Accept: application/json) — return JSON (unchanged).
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                mapOf(
                    "id" to created.id.value,
                    "name" to created.name,
                    "mediaType" to created.mediaType.mimeType,
                    "sizeBytes" to created.sizeBytes,
                    "width" to created.width,
                    "height" to created.height,
                    "contentUrl" to "/tenants/${tenantId.value}/images/${catalogSegment!!.linkFor(created.catalogKey.value)}/${created.id.value}/content",
                ),
            )
    }

    fun content(request: ServerRequest): ServerResponse {
        val tenantId = TenantKey.of(request.pathVariable("tenantId"))
        val segment = request.catalogSegment()
        val catalogId = segment.key
        // An image key is text, not a UUID: a catalog may name its images readably, and parsing
        // the path variable as a UUID made every such image unviewable and undeletable here.
        val assetId = AssetKey.of(request.pathVariable("assetId"))
        // The draft: named in the path (`letters@2.0.0`), or by the editor's `in=` for its own
        // catalog, whose image URLs it builds from a pattern that names the catalog plainly.
        val version = segment.ref(tenantId)
            ?: request.catalogParam("in")?.takeIf { it.key == catalogId }?.ref(tenantId)

        val assetContent = GetAssetContent(tenantId = tenantId, assetId = assetId, catalogKey = catalogId, catalogVersion = version).query()
            ?: return ServerResponse.notFound().build()

        return ServerResponse.ok()
            .contentType(MediaType.parseMediaType(assetContent.mediaType.mimeType))
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
            .headers { headers ->
                // Opened directly, an SVG is a document in our origin: keep it inert.
                if (SvgContentPolicy.isSvg(assetContent.mediaType)) {
                    headers.set("X-Content-Type-Options", "nosniff")
                    headers.set("Content-Security-Policy", SvgContentPolicy.SERVING_CONTENT_SECURITY_POLICY)
                }
            }
            .body(assetContent.content)
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantId = TenantKey.of(request.pathVariable("tenantId"))
        // An image key is text, not a UUID: a catalog may name its images readably, and parsing
        // the path variable as a UUID made every such image unviewable and undeletable here.
        val assetId = AssetKey.of(request.pathVariable("assetId"))
        // The list dropdown's active catalog filter rides along as a query param so
        // the refreshed grid stays consistent with the dropdown after a delete.
        val segment = request.catalogParam()
        val catalogFilter = segment?.key
        val version = request.catalogSegment().ref(tenantId)

        val force = request.param("force").orElse("false").toBoolean()

        try {
            DeleteAsset(tenantId = tenantId, assetId = assetId, force = force, catalogVersion = version).execute()
        } catch (e: AssetInUseException) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to e.message))
        }

        val tenant = GetTenant(id = tenantId).query()
        val assets = listImages(tenantId = tenantId, catalogKey = catalogFilter, version = segment?.ref(tenantId))
        return request.htmx {
            fragment("images/list", "asset-grid-items") {
                "tenantId" to tenantId.value
                "tenant" to tenant
                "assets" to assets
                "selectedCatalog" to (segment?.value ?: "")
                "draftSegment" to segment
            }
            onNonHtmx { redirect("/tenants/${tenantId.value}/images") }
        }
    }
}

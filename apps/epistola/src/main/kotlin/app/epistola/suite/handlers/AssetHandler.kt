// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets

import app.epistola.suite.assets.ImagePixelPolicy
import app.epistola.suite.assets.commands.DeleteAsset
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.assets.queries.ListAssets
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.queries.ListCatalogEditability
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.queries.ResolveCatalogAlias
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.handlers.CatalogDraftBar
import app.epistola.suite.handlers.CatalogPages
import app.epistola.suite.handlers.FromOtherCatalog
import app.epistola.suite.handlers.FromOtherCatalogs
import app.epistola.suite.htmx.CatalogSegment
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.catalogParam
import app.epistola.suite.htmx.catalogSegment
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.isHtmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
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
        catalog: app.epistola.suite.common.ids.CatalogId? = null,
        version: CatalogReleaseRef? = null,
    ): List<Asset> = ListAssets(tenantId = tenantId, searchTerm = searchTerm, catalog = catalog, catalogVersion = version)
        .query()
        .filter { it.mediaType.category != AssetMediaCategory.FONT }

    fun list(request: ServerRequest): ServerResponse = ServerResponse.ok().page("images/list") { imagePageModel(request) }

    fun search(request: ServerRequest): ServerResponse {
        val tenantId = TenantKey.of(request.pathVariable("tenantId"))
        val searchTerm = request.param("q").orElse(null)
        val segment = request.catalogParam()
        val catalogFilter = segment?.catalogIn(tenantId)
        val version = segment?.ref(tenantId)

        // HTMX search — return HTML fragment
        if (request.isHtmx) {
            val tenant = GetTenant(id = tenantId).query()
            val assets = listImages(tenantId = tenantId, searchTerm = searchTerm, catalog = catalogFilter, version = version)
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
                onFullPage { redirect(CatalogPages.list(request, tenantId, "images")) }
            }
        }

        // Editor calls with Accept: application/json
        val assets = listImages(tenantId = tenantId, searchTerm = searchTerm, catalog = catalogFilter, version = version)
        val assetInfoList = assets.map { asset ->
            mapOf(
                "id" to asset.id.value,
                "name" to asset.name,
                "mediaType" to asset.mediaType.mimeType,
                "sizeBytes" to asset.sizeBytes,
                "width" to asset.width,
                "height" to asset.height,
                "catalogKey" to asset.catalogKey.value,
                "contentUrl" to "/tenants/${tenantId.value}/catalogs/${segment?.linkFor(asset.catalogId) ?: asset.catalogId.address}/images/${asset.id.value}/content",
            )
        }
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(assetInfoList)
    }

    /**
     * Catalogs the tenant can pick images from, as JSON for the editor asset
     * picker's catalog chooser, each saying whether it can be changed here, which
     * an upload needs. Same data source as the Images list dropdown
     * ([ListCatalogs]); a UI handler, never the REST API.
     */
    fun catalogs(request: ServerRequest): ServerResponse {
        val tenantId = TenantKey.of(request.pathVariable("tenantId"))
        val editability = ListCatalogEditability(tenantId).query()
        val catalogList = ListCatalogs(tenantId).query().map { catalog ->
            mapOf(
                "key" to catalog.address,
                "name" to catalog.name,
                // Uploads go only into a catalog that can be changed here (ADR 0032).
                "editable" to editability.editable(catalog.catalogId),
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
     * and opened, uploading into the catalog it was opened on.
     */
    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = TenantKey.of(request.pathVariable("tenantId"))
        return request.htmx {
            fragment("images/new", "dialog") {
                "tenantId" to tenantId.value
                // Opened from a list filtered to a draft, the dialog creates in that draft.
                // The catalog it was opened on, a draft or plain: a dialog opened from a catalog's page
                // creates there, rather than in whichever catalog sorts first.
                "draftSegment" to request.catalogParam()
            }
            onFullPage {
                page("images/list") {
                    imagePageModel(request)
                    "openDialog" to true
                }
            }
        }
    }

    /**
     * The catalog's images as the list page shows them: the page itself, and behind the create dialog
     * when `/new` is opened directly or a create is refused without HTMX. The segment may name a draft
     * (`letters@<draft>`); its rows are then the draft's.
     */
    private fun ModelBuilder.imagePageModel(request: ServerRequest) {
        val tenantId = TenantKey.of(request.pathVariable("tenantId"))
        // The filter may name a draft (`letters@2.0.0`); its images are then the draft's.
        val segment = request.catalogSegment()
        val tenant = GetTenant(id = tenantId).query()
        val catalogs = ListCatalogs(tenantId).query()
        val assets = listImages(tenantId = tenantId, catalog = segment.catalogIn(tenantId), version = segment.ref(tenantId))
        val bar = segment.let { s -> CatalogDraftBar.of(tenantId, s) { "/tenants/${tenantId.value}/catalogs/${it.value}/images" } }
        // What the catalogs this one depends on hold of this kind, listed read-only below its own.
        val fromOtherCatalogs = FromOtherCatalogs.of(bar, { listImages(tenantId = tenantId, catalog = it) }) { a, c, n -> FromOtherCatalog(a.name, "/tenants/${tenantId.value}/catalogs/${c.address}/images", n) }
        "pageTitle" to "Images - Epistola"
        "tenantId" to tenantId.value
        "tenant" to tenant
        "catalogs" to catalogs
        "selectedCatalog" to segment.value
        "draftSegment" to segment
        "draftBar" to bar
        "fromOtherCatalogs" to fromOtherCatalogs
        "assets" to assets
        "activeNavSection" to "images"
    }

    fun upload(request: ServerRequest): ServerResponse {
        val tenantId = TenantKey.of(request.pathVariable("tenantId"))
        val multipartData = request.multipartData()

        // Accumulate field → message (the OOB per-field error track). Two keys:
        // `catalog`, and `file` — every file-related failure (missing, no content
        // type, unsupported, too large) folds into the one `file` span.
        val errors = LinkedHashMap<String, String>()

        // The catalog the dialog was opened in, or one of its drafts (`letters@<draft>`), from the path.
        val catalogSegment = request.catalogSegment()
        val catalog = catalogSegment.catalogIn(request.tenantId())

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
            // From the header only: decoding a file that declares a huge size would take the node's memory.
            ImagePixelPolicy.dimensions(mediaType, contentBytes)
        } else {
            null
        }

        var asset: Asset? = null
        if (errors.isEmpty()) {
            try {
                asset = UploadAsset(
                    catalog = catalog,
                    name = originalFilename!!,
                    mediaType = mediaType!!,
                    content = contentBytes!!,
                    width = dimensions?.first,
                    height = dimensions?.second,
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
                return request.htmx {
                    dialogFieldErrorsOob("images/new", "field-errors", errors)
                    onFullPage {
                        page(422, "images/list") {
                            imagePageModel(request)
                            "openDialog" to true
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
            val draft = catalogSegment.takeIf { it.draft != null }
            val assets = listImages(tenantId = tenantId, catalog = draft?.catalogIn(tenantId), version = draft?.ref(tenantId))
            return request.htmx {
                dialogSuccess("images/list", "asset-list", CatalogPages.list(request, tenantId, "images")) {
                    "tenantId" to tenantId.value
                    "tenant" to GetTenant(id = tenantId).query()
                    "assets" to assets
                    "selectedCatalog" to (draft?.value ?: "")
                    "draftSegment" to draft
                }
                onFullPage { redirect(CatalogPages.list(request, tenantId, "images")) }
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
                    "contentUrl" to "/tenants/${tenantId.value}/catalogs/${catalogSegment.linkFor(created.catalogId)}/images/${created.id.value}/content",
                ),
            )
    }

    fun content(request: ServerRequest): ServerResponse {
        val tenantId = TenantKey.of(request.pathVariable("tenantId"))
        val segment = request.catalogSegment()
        val catalog = segment.catalogIn(tenantId)
        // An image key is text, not a UUID: a catalog may name its images readably, and parsing
        // the path variable as a UUID made every such image unviewable and undeletable here.
        val assetId = AssetKey.of(request.pathVariable("assetId"))
        // The draft: named in the path (`letters@2.0.0`), or by the editor's `in=` for its own
        // catalog, whose image URLs it builds from a pattern that names the catalog plainly.
        val ownVersion = segment.ref(tenantId)
            ?: request.catalogParam("in")?.takeIf { it.address == segment.address }?.ref(tenantId)
        // The editor names an image's catalog by the alias its content holds, asked of the template's
        // catalog, which resolves it (ADR 0034, section 9); another catalog is read in its current version.
        val target = request.param("alias").orElse(null)?.takeIf { it.isNotBlank() }
            ?.let { ResolveCatalogAlias(catalog, it).query() ?: return ServerResponse.notFound().build() }
            ?: catalog
        val version = ownVersion.takeIf { target == catalog }

        val assetContent = GetAssetContent(tenantId = tenantId, assetId = assetId, catalog = target, catalogVersion = version).query()
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
        val catalogFilter = segment?.catalogIn(tenantId)
        val version = request.catalogSegment().ref(tenantId)

        try {
            DeleteAsset(tenantId = tenantId, assetId = assetId, catalogVersion = version).execute()
        } catch (e: AssetInUseException) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to e.message))
        }

        val tenant = GetTenant(id = tenantId).query()
        val assets = listImages(tenantId = tenantId, catalog = catalogFilter, version = segment?.ref(tenantId))
        return request.htmx {
            fragment("images/list", "asset-grid-items") {
                "tenantId" to tenantId.value
                "tenant" to tenant
                "assets" to assets
                "selectedCatalog" to (segment?.value ?: "")
                "draftSegment" to segment
            }
            onFullPage { redirect(CatalogPages.list(request, tenantId, "images")) }
        }
    }
}

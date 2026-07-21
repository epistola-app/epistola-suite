package app.epistola.suite.assets

import app.epistola.suite.assets.commands.DeleteAsset
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.assets.queries.ListAssets
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.isHtmx
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.queries.GetTenant
import jakarta.servlet.http.Part
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.util.UUID
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
    ): List<Asset> = ListAssets(tenantId = tenantId, searchTerm = searchTerm, catalogKey = catalogKey)
        .query()
        .filter { it.mediaType.category != AssetMediaCategory.FONT }

    fun list(request: ServerRequest): ServerResponse {
        val tenantId = TenantKey.of(request.pathVariable("tenantId"))
        val catalogFilter = request.param("catalog").orElse(null)?.ifBlank { null }?.let { CatalogKey.of(it) }
        val tenant = GetTenant(id = tenantId).query()
        val catalogs = ListCatalogs(tenantId).query()
        val assets = listImages(tenantId = tenantId, catalogKey = catalogFilter)
        return ServerResponse.ok().render(
            "layout/shell",
            mapOf(
                "contentView" to "images/list",
                "pageTitle" to "Images - Epistola",
                "tenantId" to tenantId.value,
                "tenant" to tenant,
                "catalogs" to catalogs,
                "selectedCatalog" to (catalogFilter?.value ?: ""),
                "assets" to assets,
                "activeNavSection" to "images",
            ),
        )
    }

    fun search(request: ServerRequest): ServerResponse {
        val tenantId = TenantKey.of(request.pathVariable("tenantId"))
        val searchTerm = request.param("q").orElse(null)
        val catalogFilter = request.param("catalog").orElse(null)?.ifBlank { null }?.let { CatalogKey.of(it) }

        // HTMX search — return HTML fragment
        if (request.isHtmx) {
            val tenant = GetTenant(id = tenantId).query()
            val assets = listImages(tenantId = tenantId, searchTerm = searchTerm, catalogKey = catalogFilter)
            return request.htmx {
                fragment("images/list", "asset-grid-items") {
                    "tenantId" to tenantId.value
                    "tenant" to tenant
                    "assets" to assets
                    "selectedCatalog" to (catalogFilter?.value ?: "")
                }
                onNonHtmx { redirect("/tenants/${tenantId.value}/images") }
            }
        }

        // Editor calls with Accept: application/json
        val assets = listImages(tenantId = tenantId, searchTerm = searchTerm, catalogKey = catalogFilter)
        val assetInfoList = assets.map { asset ->
            mapOf(
                "id" to asset.id.value.toString(),
                "name" to asset.name,
                "mediaType" to asset.mediaType.mimeType,
                "sizeBytes" to asset.sizeBytes,
                "width" to asset.width,
                "height" to asset.height,
                "catalogKey" to asset.catalogKey.value,
                "contentUrl" to "/tenants/${tenantId.value}/images/${asset.catalogKey.value}/${asset.id.value}/content",
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
        if (catalogStr == null) errors["catalog"] = "Catalog is required"
        val catalogKey = catalogStr?.let { CatalogKey.of(it) }

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
                ).execute()
            } catch (e: AssetTooLargeException) {
                errors["file"] = e.message ?: "That image is too large."
            } catch (e: CatalogReadOnlyException) {
                errors["catalog"] = e.message ?: "Catalog is read-only"
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
            val assets = listImages(tenantId = tenantId)
            return request.htmx {
                dialogSuccess("images/list", "asset-list", "/tenants/${tenantId.value}/images") {
                    "tenantId" to tenantId.value
                    "tenant" to GetTenant(id = tenantId).query()
                    "assets" to assets
                    "selectedCatalog" to ""
                }
                onNonHtmx { redirect("/tenants/${tenantId.value}/images") }
            }
        }

        // Editor API calls (Accept: application/json) — return JSON (unchanged).
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                mapOf(
                    "id" to created.id.value.toString(),
                    "name" to created.name,
                    "mediaType" to created.mediaType.mimeType,
                    "sizeBytes" to created.sizeBytes,
                    "width" to created.width,
                    "height" to created.height,
                    "contentUrl" to "/tenants/${tenantId.value}/images/${created.catalogKey.value}/${created.id.value}/content",
                ),
            )
    }

    fun content(request: ServerRequest): ServerResponse {
        val tenantId = TenantKey.of(request.pathVariable("tenantId"))
        val catalogId = CatalogKey.of(request.pathVariable("catalogId"))
        val assetId = AssetKey.of(UUID.fromString(request.pathVariable("assetId")))

        val assetContent = GetAssetContent(tenantId = tenantId, assetId = assetId, catalogKey = catalogId).query()
            ?: return ServerResponse.notFound().build()

        return ServerResponse.ok()
            .contentType(MediaType.parseMediaType(assetContent.mediaType.mimeType))
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
            .body(assetContent.content)
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantId = TenantKey.of(request.pathVariable("tenantId"))
        val assetId = AssetKey.of(UUID.fromString(request.pathVariable("assetId")))
        // The list dropdown's active catalog filter rides along as a query param so
        // the refreshed grid stays consistent with the dropdown after a delete.
        val catalogFilter = request.param("catalog").orElse(null)?.ifBlank { null }?.let { CatalogKey.of(it) }

        val force = request.param("force").orElse("false").toBoolean()

        try {
            DeleteAsset(tenantId = tenantId, assetId = assetId, force = force).execute()
        } catch (e: AssetInUseException) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to e.message))
        }

        val tenant = GetTenant(id = tenantId).query()
        val assets = listImages(tenantId = tenantId, catalogKey = catalogFilter)
        return request.htmx {
            fragment("images/list", "asset-grid-items") {
                "tenantId" to tenantId.value
                "tenant" to tenant
                "assets" to assets
                "selectedCatalog" to (catalogFilter?.value ?: "")
            }
            onNonHtmx { redirect("/tenants/${tenantId.value}/images") }
        }
    }
}

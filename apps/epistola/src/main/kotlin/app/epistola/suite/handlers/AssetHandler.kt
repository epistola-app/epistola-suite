package app.epistola.suite.assets

import app.epistola.suite.assets.AssetInUseException
import app.epistola.suite.assets.commands.DeleteAsset
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.assets.queries.ListAssets
import app.epistola.suite.common.ids.AssetId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.isHtmx
import app.epistola.suite.htmx.redirect
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
class AssetHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun list(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        val tenant = GetTenant(id = tenantId).query()
        val assets = ListAssets(tenantId = tenantId).query()
        return ServerResponse.ok().render(
            "layout/shell",
            mapOf(
                "contentView" to "assets/list",
                "pageTitle" to "Assets - Epistola",
                "tenantId" to tenantId.value,
                "tenant" to tenant,
                "assets" to assets,
                "activeNavSection" to "assets",
            ),
        )
    }

    fun search(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        val searchTerm = request.param("q").orElse(null)

        // HTMX search — return HTML fragment
        if (request.isHtmx) {
            val tenant = GetTenant(id = tenantId).query()
            val assets = ListAssets(tenantId = tenantId, searchTerm = searchTerm).query()
            return request.htmx {
                fragment("assets/list", "asset-grid-items") {
                    "tenantId" to tenantId.value
                    "tenant" to tenant
                    "assets" to assets
                }
                onNonHtmx { redirect("/tenants/${tenantId.value}/assets") }
            }
        }

        // Editor calls with Accept: application/json
        val assets = ListAssets(tenantId = tenantId, searchTerm = searchTerm).query()
        val assetInfoList = assets.map { asset ->
            mapOf(
                "id" to asset.id.value.toString(),
                "name" to asset.name,
                "mediaType" to asset.mediaType.mimeType,
                "sizeBytes" to asset.sizeBytes,
                "width" to asset.width,
                "height" to asset.height,
                "contentUrl" to "/tenants/${tenantId.value}/assets/${asset.id.value}/content",
            )
        }
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(assetInfoList)
    }

    fun upload(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))

        val multipartData = request.multipartData()
        val filePart: Part = multipartData["file"]?.firstOrNull()
            ?: return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to "No file provided"))

        val contentBytes = filePart.inputStream.use { it.readAllBytes() }
        val originalFilename = filePart.submittedFileName ?: "unnamed"
        val contentType = filePart.contentType
            ?: return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to "No content type on uploaded file"))

        val mediaType = try {
            AssetMediaType.fromMimeType(contentType)
        } catch (e: UnsupportedAssetTypeException) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to e.message))
        }

        // Extract image dimensions (null for SVG)
        val dimensions = if (mediaType != AssetMediaType.SVG) {
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

        val asset = try {
            UploadAsset(
                tenantId = tenantId,
                name = originalFilename,
                mediaType = mediaType,
                content = contentBytes,
                width = dimensions?.first,
                height = dimensions?.second,
            ).execute()
        } catch (e: AssetTooLargeException) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to e.message))
        }

        // HTMX: return updated asset grid
        if (request.isHtmx) {
            val tenant = GetTenant(id = tenantId).query()
            val assets = ListAssets(tenantId = tenantId).query()
            return request.htmx {
                fragment("assets/list", "asset-grid-items") {
                    "tenantId" to tenantId.value
                    "tenant" to tenant
                    "assets" to assets
                }
                onNonHtmx { redirect("/tenants/${tenantId.value}/assets") }
            }
        }

        // Editor calls — return JSON
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                mapOf(
                    "id" to asset.id.value.toString(),
                    "name" to asset.name,
                    "mediaType" to asset.mediaType.mimeType,
                    "sizeBytes" to asset.sizeBytes,
                    "width" to asset.width,
                    "height" to asset.height,
                    "contentUrl" to "/tenants/${tenantId.value}/assets/${asset.id.value}/content",
                ),
            )
    }

    fun content(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        val assetId = AssetId.of(UUID.fromString(request.pathVariable("assetId")))

        val assetContent = GetAssetContent(tenantId = tenantId, assetId = assetId).query()
            ?: return ServerResponse.notFound().build()

        return ServerResponse.ok()
            .contentType(MediaType.parseMediaType(assetContent.mediaType.mimeType))
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
            .body(assetContent.content)
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        val assetId = AssetId.of(UUID.fromString(request.pathVariable("assetId")))

        try {
            DeleteAsset(tenantId = tenantId, assetId = assetId).execute()
        } catch (e: AssetInUseException) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to e.message))
        }

        val tenant = GetTenant(id = tenantId).query()
        val assets = ListAssets(tenantId = tenantId).query()
        return request.htmx {
            fragment("assets/list", "asset-grid-items") {
                "tenantId" to tenantId.value
                "tenant" to tenant
                "assets" to assets
            }
            onNonHtmx { redirect("/tenants/${tenantId.value}/assets") }
        }
    }
}

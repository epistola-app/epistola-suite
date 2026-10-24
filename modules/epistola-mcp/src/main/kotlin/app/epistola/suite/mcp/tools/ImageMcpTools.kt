// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.tools

import app.epistola.suite.assets.AssetMediaCategory
import app.epistola.suite.assets.AssetTypeCatalog
import app.epistola.suite.assets.ImagePixelPolicy
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.assets.queries.ListAssets
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.mcp.dto.ImageInfo
import app.epistola.suite.mcp.support.CATALOG_VERSION_DESCRIPTION
import app.epistola.suite.mcp.support.catalogEditability
import app.epistola.suite.mcp.support.catalogFilter
import app.epistola.suite.mcp.support.catalogVersionRef
import app.epistola.suite.mcp.support.decodeBase64Argument
import app.epistola.suite.mcp.support.mcpCatalog
import app.epistola.suite.mcp.support.mcpTenantKey
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.execute
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import javax.imageio.ImageIO

/**
 * Images referenced by template documents and stencils: discovery, and upload so an assistant
 * converting a document can bring its logos and pictures along. Font binaries are uploaded
 * through `upload_font`, never here, as in the REST images API.
 */
@Component
class ImageMcpTools(
    private val mediator: Mediator,
    private val assetTypeCatalog: AssetTypeCatalog,
) {

    @McpTool(
        name = "list_images",
        description = "List image metadata in the current tenant, optionally filtered by catalog " +
            "or name. Returns IDs, media types, dimensions, sizes, and catalog provenance; " +
            "binary image content is intentionally excluded.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listImages(
        @McpToolParam(description = "Catalog slug to filter by. Omit to list across all catalogs.", required = false)
        catalogId: String?,
        @McpToolParam(description = "Case-insensitive image-name search. Omit for all images.", required = false)
        search: String?,
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
    ): List<ImageInfo> {
        val editability = catalogEditability()
        return mediator.query(
            ListAssets(
                tenantId = mcpTenantKey(),
                searchTerm = search?.takeIf { it.isNotBlank() },
                catalog = catalogFilter(catalogId),
                catalogVersion = catalogVersionRef(catalogId, catalogVersion),
            ),
        )
            .asSequence()
            .filter { it.mediaType.category == AssetMediaCategory.IMAGE }
            .map { ImageInfo.from(it, editability.editable(it.catalogId)) }
            .toList()
    }

    @McpTool(
        name = "get_image",
        description = "Get metadata for one image by catalog and UUID. Use this to verify the " +
            "identity, media type, dimensions, size, and catalog provenance of an image reference.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun getImage(
        @McpToolParam(description = "Catalog key containing the image.")
        catalogId: String,
        @McpToolParam(description = "Image UUID referenced by the template or stencil.")
        imageId: String,
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
    ): ImageInfo? {
        val key = AssetKey.of(imageId)
        return mediator.query(
            ListAssets(
                tenantId = mcpTenantKey(),
                catalog = mcpCatalog(catalogId),
                catalogVersion = catalogVersionRef(catalogId, catalogVersion),
            ),
        )
            .firstOrNull { it.id == key && it.mediaType.category == AssetMediaCategory.IMAGE }
            ?.let { ImageInfo.from(it, catalogEditability().editable(it.catalogId)) }
    }

    @McpTool(
        name = "upload_image",
        description = "Upload an image (PNG, JPEG, SVG, ...) to a catalog that can be changed here so templates and stencils " +
            "can show it. Returns the image's `id`; an `image` node references it with the props `assetId` (the `id`) " +
            "and `catalogKey` (the `catalogId`). Images are immutable: upload " +
            "a changed image as a new one. Requires the TEMPLATE_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false),
    )
    fun uploadImage(
        @McpToolParam(description = "Catalog key to upload into. Must be a catalog that can be changed here (`editable` in `list_catalogs`).")
        catalogId: String,
        @McpToolParam(description = "Display name, e.g. the original file name.")
        name: String,
        @McpToolParam(description = "Image bytes as base64, or as a `data:<media type>;base64,...` URL.")
        content: String,
        @McpToolParam(
            description = "Media type, e.g. `image/png`. Optional when `content` is a data URL.",
            required = false,
        )
        mediaType: String?,
        @McpToolParam(
            description = "Readable key for the image, e.g. `company-logo` (lowercase letters, digits, hyphens). " +
                "Omit to have one generated.",
            required = false,
        )
        imageId: String?,
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
    ): ImageInfo {
        val decoded = decodeBase64Argument("content", content)
        val declared = mediaType?.takeIf { it.isNotBlank() } ?: decoded.declaredMediaType
            ?: throw ValidationException("mediaType", "`mediaType` is required unless `content` is a data URL")
        val resolved = assetTypeCatalog.require(declared)
        if (resolved.category != AssetMediaCategory.IMAGE) {
            throw ValidationException(
                field = "mediaType",
                message = "'${resolved.mimeType}' is not an image. Upload fonts with `upload_font`.",
                code = ValidationCode.IMAGE_MEDIA_TYPE_UNSUPPORTED,
            )
        }
        val dimensions = if (resolved.mimeType != "image/svg+xml") imageDimensions(decoded.bytes) else null
        return ImageInfo.from(
            UploadAsset(
                catalog = mcpCatalog(catalogId),
                name = name,
                mediaType = resolved,
                content = decoded.bytes,
                width = dimensions?.first,
                height = dimensions?.second,
                id = imageId?.takeIf { it.isNotBlank() }?.let { AssetKey.of(it) },
                catalogVersion = catalogVersionRef(catalogId, catalogVersion),
            ).execute(),
            // The upload went through, so the catalog can be changed here.
            editable = true,
        )
    }

    // From the header only: decoding a file that declares a huge size would take the node's memory.
    private fun imageDimensions(content: ByteArray): Pair<Int, Int>? = ImagePixelPolicy.dimensions(content)
}

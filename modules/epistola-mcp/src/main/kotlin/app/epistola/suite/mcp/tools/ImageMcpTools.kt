// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.tools

import app.epistola.suite.assets.AssetMediaCategory
import app.epistola.suite.assets.queries.ListAssets
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.mcp.dto.ImageInfo
import app.epistola.suite.mcp.support.mcpTenantKey
import app.epistola.suite.mediator.Mediator
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

/** Read-only discovery of images referenced by template documents and stencils. */
@Component
class ImageMcpTools(
    private val mediator: Mediator,
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
    ): List<ImageInfo> = mediator.query(
        ListAssets(
            tenantId = mcpTenantKey(),
            searchTerm = search?.takeIf { it.isNotBlank() },
            catalogKey = catalogId?.takeIf { it.isNotBlank() }?.let { CatalogKey.of(it) },
        ),
    )
        .asSequence()
        .filter { it.mediaType.category == AssetMediaCategory.IMAGE }
        .map(ImageInfo::from)
        .toList()

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
    ): ImageInfo? {
        val key = AssetKey.of(imageId)
        return mediator.query(
            ListAssets(
                tenantId = mcpTenantKey(),
                catalogKey = CatalogKey.of(catalogId),
            ),
        )
            .firstOrNull { it.id == key && it.mediaType.category == AssetMediaCategory.IMAGE }
            ?.let(ImageInfo::from)
    }
}

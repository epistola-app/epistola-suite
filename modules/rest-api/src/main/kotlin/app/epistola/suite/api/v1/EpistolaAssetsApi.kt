// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.AssetsApi
import app.epistola.api.model.AssetDto
import app.epistola.api.model.AssetListResponse
import app.epistola.suite.api.v1.shared.Pagination
import app.epistola.suite.api.v1.shared.toDto
import app.epistola.suite.assets.AssetNotFoundException
import app.epistola.suite.assets.AssetTypeCatalog
import app.epistola.suite.assets.commands.DeleteAsset
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.assets.queries.GetAsset
import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.assets.queries.ListAssets
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID
import javax.imageio.ImageIO

@RestController
@RequestMapping("/api")
class EpistolaAssetsApi(
    private val assetTypeCatalog: AssetTypeCatalog,
) : AssetsApi {

    override fun listAssets(
        tenantId: String,
        catalogId: String,
        search: String?,
        mediaCategory: String?,
        page: Int,
        size: Int,
    ): ResponseEntity<AssetListResponse> {
        val assets = ListAssets(
            tenantId = TenantKey.of(tenantId),
            searchTerm = search,
            catalogKey = CatalogKey.of(catalogId),
        ).query()
        val filtered = mediaCategory?.let { requested ->
            assets.filter { it.mediaType.category.name.equals(requested, ignoreCase = true) }
        } ?: assets
        val slice = Pagination.paginate(filtered, page, size)
        return ResponseEntity.ok(AssetListResponse(items = slice.items.map { it.toDto() }, page = slice.page))
    }

    override fun uploadAsset(
        tenantId: String,
        catalogId: String,
        file: MultipartFile,
        name: String?,
        mediaType: String?,
        sensitive: Boolean,
    ): ResponseEntity<AssetDto> {
        val content = file.bytes
        val resolvedMediaType = assetTypeCatalog.require(
            mediaType?.takeIf { it.isNotBlank() } ?: file.contentType ?: MediaType.APPLICATION_OCTET_STREAM_VALUE,
        )
        val dimensions = if (resolvedMediaType.category.name == "IMAGE" && resolvedMediaType.mimeType != "image/svg+xml") {
            imageDimensions(content)
        } else {
            null
        }
        val asset = UploadAsset(
            tenantId = TenantKey.of(tenantId),
            name = name?.takeIf { it.isNotBlank() } ?: file.originalFilename ?: "unnamed",
            mediaType = resolvedMediaType,
            content = content,
            width = dimensions?.first,
            height = dimensions?.second,
            catalogKey = CatalogKey.of(catalogId),
            sensitive = sensitive,
        ).execute()

        return ResponseEntity.status(HttpStatus.CREATED).body(asset.toDto())
    }

    override fun downloadAssetContent(
        tenantId: String,
        catalogId: String,
        assetId: UUID,
    ): ResponseEntity<Resource> {
        val id = AssetKey(assetId)
        val content = GetAssetContent(
            tenantId = TenantKey.of(tenantId),
            catalogKey = CatalogKey.of(catalogId),
            assetId = id,
        ).query() ?: throw AssetNotFoundException(TenantKey.of(tenantId), id)

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(content.mediaType.mimeType))
            .header(HttpHeaders.CONTENT_LENGTH, content.content.size.toString())
            .body(ByteArrayResource(content.content))
    }

    override fun deleteAsset(
        tenantId: String,
        catalogId: String,
        assetId: UUID,
        force: Boolean,
    ): ResponseEntity<Unit> {
        val id = AssetKey(assetId)
        val tenantKey = TenantKey.of(tenantId)
        val catalogKey = CatalogKey.of(catalogId)
        val asset = GetAsset(tenantId = tenantKey, assetId = id).query()
        if (asset?.catalogKey != catalogKey) {
            throw AssetNotFoundException(tenantKey, id)
        }
        val deleted = DeleteAsset(
            tenantId = tenantKey,
            assetId = id,
            force = force,
        ).execute()
        return if (deleted) {
            ResponseEntity.noContent().build()
        } else {
            throw AssetNotFoundException(tenantKey, id)
        }
    }

    private fun imageDimensions(content: ByteArray): Pair<Int, Int>? = try {
        content.inputStream().use { stream ->
            ImageIO.read(stream)?.let { it.width to it.height }
        }
    } catch (_: Exception) {
        null
    }
}

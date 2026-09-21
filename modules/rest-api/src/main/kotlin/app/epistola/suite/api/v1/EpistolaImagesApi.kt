// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.ImagesApi
import app.epistola.api.model.ImageDto
import app.epistola.api.model.ImageListResponse
import app.epistola.suite.api.v1.shared.Pagination
import app.epistola.suite.api.v1.shared.toImageDto
import app.epistola.suite.assets.AssetMediaCategory
import app.epistola.suite.assets.AssetNotFoundException
import app.epistola.suite.assets.AssetTypeCatalog
import app.epistola.suite.assets.commands.DeleteAsset
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.assets.queries.GetAsset
import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.assets.queries.ListImagePage
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import javax.imageio.ImageIO

/**
 * Images, addressed by slug.
 *
 * Succeeds the asset operations, which are deprecated. Two differences matter. An image is
 * addressed by a `slug` that is a plain string, so a catalog naming its images readably --
 * `municipality-mark` -- can be served; the asset path takes a UUID and cannot. And this lists
 * images only, where listing assets mixed in the font-face binaries that back a font family and
 * that nobody works with directly.
 *
 * The rows are the same either way: images and font faces share one content-addressed store,
 * which is a storage concern rather than something a caller should have to know about.
 */
@RestController
@RequestMapping("/api")
class EpistolaImagesApi(
    private val assetTypeCatalog: AssetTypeCatalog,
) : ImagesApi {

    override fun listImages(
        tenantId: String,
        catalogId: String,
        search: String?,
        page: Int,
        size: Int,
    ): ResponseEntity<ImageListResponse> {
        // Paged in the database. A tenant's image count is unbounded, so the page a caller asked
        // for should cost the page rather than the whole catalog.
        val result = ListImagePage(
            tenantKey = TenantKey.of(tenantId),
            catalogKey = CatalogKey.of(catalogId),
            searchTerm = search,
            limit = Pagination.limitOf(size),
            offset = Pagination.offsetOf(page, size),
        ).query()
        return ResponseEntity.ok(
            ImageListResponse(
                items = result.items.map { it.toImageDto() },
                page = Pagination.pageMeta(page, size, result.totalElements),
            ),
        )
    }

    override fun uploadImage(
        tenantId: String,
        catalogId: String,
        file: MultipartFile,
        name: String?,
        mediaType: String?,
        sensitive: Boolean,
    ): ResponseEntity<ImageDto> {
        val content = file.bytes
        val resolvedMediaType = assetTypeCatalog.require(
            mediaType?.takeIf { it.isNotBlank() } ?: file.contentType ?: MediaType.APPLICATION_OCTET_STREAM_VALUE,
        )
        // Images only, unlike the asset upload it succeeds. A font face binary means something
        // only as part of a family, and families are managed through the UI and catalog exchange.
        if (resolvedMediaType.category != AssetMediaCategory.IMAGE) {
            throw ValidationException(
                field = "mediaType",
                message = "'${resolvedMediaType.mimeType}' is not an image. Font binaries are managed through the fonts API.",
                code = ValidationCode.IMAGE_MEDIA_TYPE_UNSUPPORTED,
            )
        }
        val dimensions = if (resolvedMediaType.mimeType != "image/svg+xml") imageDimensions(content) else null
        val image = UploadAsset(
            tenantId = TenantKey.of(tenantId),
            name = name?.takeIf { it.isNotBlank() } ?: file.originalFilename ?: "unnamed",
            mediaType = resolvedMediaType,
            content = content,
            width = dimensions?.first,
            height = dimensions?.second,
            catalogKey = CatalogKey.of(catalogId),
            sensitive = sensitive,
        ).execute()
        return ResponseEntity.status(HttpStatus.CREATED).body(image.toImageDto())
    }

    override fun downloadImageContent(
        tenantId: String,
        catalogId: String,
        imageSlug: String,
    ): ResponseEntity<Resource> {
        val slug = AssetKey.of(imageSlug)
        val content = GetAssetContent(
            tenantId = TenantKey.of(tenantId),
            catalogKey = CatalogKey.of(catalogId),
            assetId = slug,
        ).query() ?: throw AssetNotFoundException(TenantKey.of(tenantId), slug)

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(content.mediaType.mimeType))
            .header(HttpHeaders.CONTENT_LENGTH, content.content.size.toString())
            .body(ByteArrayResource(content.content))
    }

    override fun deleteImage(
        tenantId: String,
        catalogId: String,
        imageSlug: String,
        force: Boolean,
    ): ResponseEntity<Unit> {
        val slug = AssetKey.of(imageSlug)
        val tenantKey = TenantKey.of(tenantId)
        val catalogKey = CatalogKey.of(catalogId)
        val image = GetAsset(tenantId = tenantKey, assetId = slug).query()
        if (image?.catalogKey != catalogKey || image.mediaType.category != AssetMediaCategory.IMAGE) {
            throw AssetNotFoundException(tenantKey, slug)
        }
        DeleteAsset(tenantId = tenantKey, assetId = slug, force = force).execute()
        return ResponseEntity.noContent().build()
    }

    private fun imageDimensions(content: ByteArray): Pair<Int, Int>? = runCatching {
        ImageIO.read(content.inputStream())?.let { it.width to it.height }
    }.getOrNull()
}

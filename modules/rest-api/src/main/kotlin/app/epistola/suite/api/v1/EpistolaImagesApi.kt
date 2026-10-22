// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.ImagesApi
import app.epistola.api.model.ImageDto
import app.epistola.api.model.ImageListResponse
import app.epistola.catalog.protocol.ImageResource
import app.epistola.suite.api.v1.shared.Pagination
import app.epistola.suite.api.v1.shared.catalogVersionAt
import app.epistola.suite.api.v1.shared.created
import app.epistola.suite.api.v1.shared.draftAt
import app.epistola.suite.api.v1.shared.readOnlyHere
import app.epistola.suite.api.v1.shared.ref
import app.epistola.suite.api.v1.shared.releasedResource
import app.epistola.suite.api.v1.shared.releasedResources
import app.epistola.suite.api.v1.shared.tenantKey
import app.epistola.suite.api.v1.shared.toDto
import app.epistola.suite.api.v1.shared.toImageDto
import app.epistola.suite.assets.AssetMediaCategory
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.AssetNotFoundException
import app.epistola.suite.assets.AssetTypeCatalog
import app.epistola.suite.assets.ImagePixelPolicy
import app.epistola.suite.assets.SvgContentPolicy
import app.epistola.suite.assets.commands.DeleteAsset
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.assets.queries.GetAsset
import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.assets.queries.ListImagePage
import app.epistola.suite.catalog.releases.GetReleasedBinary
import app.epistola.suite.common.ids.AssetKey
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
 *
 * A draft reads and changes the working copy; a released version reads the images the release holds.
 */
@RestController
@RequestMapping("/api")
class EpistolaImagesApi(
    private val assetTypeCatalog: AssetTypeCatalog,
) : ImagesApi {

    override fun listImages(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        search: String?,
        page: Int,
        size: Int,
    ): ResponseEntity<ImageListResponse> {
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, release)
        if (!at.isDraft) {
            // Released content is paged in memory: it is assembled whole, and bounded by the catalog.
            val images = at.releasedResources<ImageResource>("image").filter { search.isNullOrBlank() || it.name.contains(search, ignoreCase = true) }
            val slice = Pagination.pageOfRelease(images, page, size)
            return ResponseEntity.ok(
                ImageListResponse(
                    items = slice.items.map { it.toDto(at, sizeBytes = releasedBytes(at.tenantKey, it).size.toLong()) },
                    page = slice.page,
                ),
            )
        }
        // Paged in the database. A tenant's image count is unbounded, so the page a caller asked
        // for should cost the page rather than the whole catalog.
        val result = ListImagePage(
            tenantKey = at.tenantKey,
            catalogKey = at.catalogKey,
            searchTerm = search,
            limit = Pagination.limitOf(size),
            offset = Pagination.offsetOf(page, size),
            catalogVersion = at.ref,
        ).query()
        return ResponseEntity.ok(
            ImageListResponse(
                items = at.catalog.readOnlyHere().let { readOnly -> result.items.map { it.toImageDto(at.catalog, readOnly) } },
                page = Pagination.pageMeta(page, size, result.totalElements),
            ),
        )
    }

    override fun uploadImage(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        file: MultipartFile,
        name: String?,
        mediaType: String?,
        sensitive: Boolean,
    ): ResponseEntity<ImageDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
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
            tenantId = draft.tenantKey,
            name = name?.takeIf { it.isNotBlank() } ?: file.originalFilename ?: "unnamed",
            mediaType = resolvedMediaType,
            content = content,
            width = dimensions?.first,
            height = dimensions?.second,
            catalogKey = draft.catalogKey,
            sensitive = sensitive,
            catalogVersion = draft.ref,
        ).execute()
        return image.toImageDto(draft.catalog).let { created(it.key, it) }
    }

    override fun downloadImageContent(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        imageKey: String,
    ): ResponseEntity<Resource> {
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, release)
        val key = AssetKey.of(imageKey)
        val (mediaType, bytes) = if (at.isDraft) {
            GetAssetContent(tenantId = at.tenantKey, catalogKey = at.catalogKey, assetId = key, catalogVersion = at.ref).query()
                ?.let { it.mediaType to it.content }
        } else {
            at.releasedResource<ImageResource>("image", imageKey)
                ?.let { AssetMediaType(it.mediaType) to releasedBytes(at.tenantKey, it) }
        } ?: throw AssetNotFoundException(at.tenantKey, key)

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(mediaType.mimeType))
            .header(HttpHeaders.CONTENT_LENGTH, bytes.size.toString())
            .headers { headers ->
                // Opened directly, an SVG is a document in our origin: keep it inert.
                if (SvgContentPolicy.isSvg(mediaType)) {
                    headers.set("X-Content-Type-Options", "nosniff")
                    headers.set("Content-Security-Policy", SvgContentPolicy.SERVING_CONTENT_SECURITY_POLICY)
                }
            }
            .body(ByteArrayResource(bytes))
    }

    override fun deleteImage(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        imageKey: String,
        force: Boolean,
    ): ResponseEntity<Unit> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        val key = AssetKey.of(imageKey)
        val image = GetAsset(tenantId = draft.tenantKey, assetId = key, catalogVersion = draft.ref).query()
        if (image?.catalogKey != draft.catalogKey || image.mediaType.category != AssetMediaCategory.IMAGE) {
            throw AssetNotFoundException(draft.tenantKey, key)
        }
        DeleteAsset(tenantId = draft.tenantKey, assetId = key, force = force, catalogVersion = draft.ref).execute()
        return ResponseEntity.noContent().build()
    }

    /** The bytes a release holds for [image]; a release that names bytes it does not hold is not served. */
    private fun releasedBytes(tenantKey: TenantKey, image: ImageResource): ByteArray = GetReleasedBinary(tenantKey, image.contentHash).query()
        ?: throw AssetNotFoundException(tenantKey, AssetKey.of(image.key))

    // From the header only: decoding a file that declares a huge size would take the node's memory.
    private fun imageDimensions(content: ByteArray): Pair<Int, Int>? = ImagePixelPolicy.dimensions(content)
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets

import app.epistola.catalog.protocol.ImageResource
import app.epistola.suite.assets.commands.ImportAsset
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.releases.ContentVersion
import app.epistola.suite.catalog.types.Arrival
import app.epistola.suite.catalog.types.ResourceType
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.mediator.execute
import org.springframework.stereotype.Component

/** An image as a catalog resource type (ADR 0039): its bytes are a binary of its own, carried by the archive. */
@Component
class ImageResourceType : ResourceType<ImageResource> {
    override val name = ImageStore.IMAGE
    override val wireClass = ImageResource::class.java

    override fun receive(at: ContentVersion, resource: ImageResource, arrival: Arrival): InstallStatus {
        val content = arrival.binaries(resource.contentPath())
            ?: throw IllegalArgumentException("Missing image content: ${resource.contentPath()}")
        return ImportAsset(
            catalog = at.catalog,
            catalogVersion = at.ref,
            // A published catalog may name its images the way a person would (`municipality-mark`).
            id = AssetKey.of(resource.key),
            name = resource.name,
            mediaType = AssetMediaType.fromMimeType(resource.mediaType),
            content = content,
            width = resource.width,
            height = resource.height,
            // As received: none clears what the draft held.
            metadata = resource.metadata.orEmpty(),
        ).execute()
    }

    /** The checks an upload makes: an SVG inert and self-contained, a raster image of a size a render can decode. */
    override fun requireAcceptable(resource: ImageResource, binaries: (String) -> ByteArray?) {
        val content = binaries(resource.contentPath()) ?: return
        val mediaType = AssetMediaType.fromMimeType(resource.mediaType)
        SvgContentPolicy.requireSafe(mediaType, content)
        ImagePixelPolicy.requireReasonable(mediaType, content)
    }
}

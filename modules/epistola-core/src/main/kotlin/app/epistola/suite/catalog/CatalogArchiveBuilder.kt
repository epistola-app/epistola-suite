// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.catalog.archive.CatalogArchive
import app.epistola.catalog.archive.CatalogArchivePolicy
import app.epistola.catalog.archive.CatalogArchiveWriter
import app.epistola.catalog.protocol.ReleaseInfo
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Serializes already-built content with an exact release descriptor. */
@Component
class CatalogArchiveBuilder(
    private val sizeLimits: CatalogSizeLimits,
) {
    fun build(content: CatalogContent, release: ReleaseInfo): ByteArray {
        val assetContent = content.assetContents.mapKeys { (filename, _) -> "resources/asset/$filename" }
        val portableArchive = CatalogArchive(
            manifest = content.toManifest(release),
            resourceDetails = content.resourceDetails,
            paths = assetContent.keys,
            content = { path ->
                ByteArrayInputStream(requireNotNull(assetContent[path]) { "Missing catalog asset: $path" })
            },
        )
        val output = ByteArrayOutputStream()
        portableArchive.use {
            CatalogArchiveWriter.write(
                it,
                output,
                CatalogArchivePolicy(
                    maxCompressedBytes = sizeLimits.maxZipSize.toBytes(),
                    maxExpandedBytes = sizeLimits.maxDecompressedSize.toBytes(),
                ),
            )
        }
        return output.toByteArray()
    }
}

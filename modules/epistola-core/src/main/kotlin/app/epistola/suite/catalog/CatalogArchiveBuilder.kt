// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.catalog.archive.CatalogArchive
import app.epistola.catalog.archive.CatalogArchivePolicy
import app.epistola.catalog.archive.CatalogArchiveWriter
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.ReleaseInfo
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Serializes already-built content with an exact release descriptor. */
@Component
class CatalogArchiveBuilder(
    private val sizeLimits: CatalogSizeLimits,
) {
    fun build(content: CatalogContent, release: ReleaseInfo): ByteArray = build(content, content.toManifest(release))

    /** For callers that already built the manifest — releasing keeps a copy for its snapshot. */
    fun build(content: CatalogContent, manifest: CatalogManifest): ByteArray {
        // Already keyed by the archive path each binary takes -- `bin/<hash>` for a catalog
        // written at wire v7, or the path a migrated archive declared. Prefixing here would bury
        // it under a second directory.
        val assetContent = content.assetContents
        val portableArchive = CatalogArchive(
            manifest = manifest,
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

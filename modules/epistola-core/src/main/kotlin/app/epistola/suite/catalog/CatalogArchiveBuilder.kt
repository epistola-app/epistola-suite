// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.catalog.archive.CatalogArchive
import app.epistola.catalog.archive.CatalogArchivePolicy
import app.epistola.catalog.archive.CatalogArchiveWriter
import app.epistola.catalog.archive.EmbeddedCatalogRelease
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.ReleaseInfo
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** A release of another catalog an archive carries for a pin (wire v8 `dependencies/`). */
data class CarriedRelease(
    val registry: String,
    val namespace: String,
    val catalogKey: String,
    val content: CatalogContent,
    val release: ReleaseInfo,
)

/** Serializes already-built content with an exact release descriptor. */
@Component
class CatalogArchiveBuilder(
    private val sizeLimits: CatalogSizeLimits,
) {
    fun build(content: CatalogContent, release: ReleaseInfo): ByteArray = build(content, content.toManifest(release))

    /**
     * [content] as release [release], carrying [carried] at `dependencies/…` (wire v8), in the order
     * given: each before every release that pins it. The manifest and fingerprint are the same as
     * without them.
     */
    fun build(content: CatalogContent, release: ReleaseInfo, carried: List<CarriedRelease>): ByteArray = write(
        portable(content, content.toManifest(release)).let { root ->
            CatalogArchive(
                manifest = root.manifest,
                resourceDetails = root.resourceDetails,
                paths = root.paths,
                content = root.content,
                dependencies = carried.map {
                    EmbeddedCatalogRelease(it.registry, it.namespace, it.catalogKey, it.release.version, portable(it.content, it.content.toManifest(it.release)))
                },
            )
        },
    )

    /** For callers that already built the manifest — releasing keeps a copy for its snapshot. */
    fun build(content: CatalogContent, manifest: CatalogManifest): ByteArray = write(portable(content, manifest))

    private fun portable(content: CatalogContent, manifest: CatalogManifest): CatalogArchive {
        // Already keyed by the archive path each binary takes -- `bin/<hash>` for a catalog
        // written at wire v7, or the path a migrated archive declared. Prefixing here would bury
        // it under a second directory.
        val assetContent = content.assetContents
        return CatalogArchive(
            manifest = manifest,
            resourceDetails = content.resourceDetails,
            paths = assetContent.keys,
            content = { path ->
                ByteArrayInputStream(requireNotNull(assetContent[path]) { "Missing catalog asset: $path" })
            },
        )
    }

    private fun write(portableArchive: CatalogArchive): ByteArray {
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

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.archive.CatalogArchive
import app.epistola.catalog.archive.CatalogArchivePolicy
import app.epistola.catalog.archive.CatalogArchiveWriter
import app.epistola.catalog.protocol.ReleaseInfo
import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogSizeLimits
import app.epistola.suite.catalog.MultipleStencilVersionsInUseException
import app.epistola.suite.catalog.queries.FindStencilVersionExportConflicts
import app.epistola.suite.catalog.queries.GetLatestCatalogRelease
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.time.EpistolaClock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Exports all resources in a catalog as a self-contained ZIP archive.
 * Content is assembled by [CatalogContentBuilder] (shared with fingerprinting),
 * so the exported bytes are exactly the fingerprinted bytes.
 */
data class ExportCatalogZip(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Command<ExportCatalogZipResult>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

data class ExportCatalogZipResult(
    val zipBytes: ByteArray,
    val filename: String,
)

@Component
class ExportCatalogZipHandler(
    private val contentBuilder: CatalogContentBuilder,
    private val fingerprintService: CatalogFingerprintService,
    private val sizeLimits: CatalogSizeLimits,
) : CommandHandler<ExportCatalogZip, ExportCatalogZipResult> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: ExportCatalogZip): ExportCatalogZipResult {
        // Block export when published templates pin the same own-catalog stencil
        // at more than one version — the wire format only carries one version per
        // stencil, so a downstream import would silently break the divergent uses.
        val conflicts = FindStencilVersionExportConflicts(command.tenantKey, command.catalogKey).query()
        if (conflicts.isNotEmpty()) {
            throw MultipleStencilVersionsInUseException(command.catalogKey, conflicts)
        }

        val content = contentBuilder.build(command.tenantKey, command.catalogKey)
        fingerprintService.requirePublishable(content)

        // The emitted fingerprint always describes the actual exported bytes.
        // The version label encodes release state: a clean released version
        // when the working copy matches the latest release, a `-dev`-suffixed
        // label when it drifted (unreleased edits) or was never released.
        // Export is never hard-blocked — `-dev` makes drift unmistakable.
        val fingerprint = fingerprintService.fingerprint(content)
        // Cheap release-pointer read — no second O(catalog-size) content build
        // (the working-copy fingerprint we need is already `fingerprint`).
        val release = GetLatestCatalogRelease(command.tenantKey, command.catalogKey).query()
        val version = when {
            release.latestVersion == null -> {
                logger.warn("Exporting never-released catalog '{}' as 0.0.0-dev", command.catalogKey.value)
                "0.0.0-dev"
            }
            release.latestFingerprint != null && !fingerprintService.matchesFingerprint(content, release.latestFingerprint) -> {
                logger.warn(
                    "Exporting catalog '{}' with unreleased changes — labelling {}-dev",
                    command.catalogKey.value,
                    release.latestVersion,
                )
                "${release.latestVersion}-dev"
            }
            else -> release.latestVersion
        }
        val releasedAt = if (version.endsWith("-dev")) null else EpistolaClock.offsetDateTime().toString()
        val manifest = content.toManifest(
            ReleaseInfo(version = version, releasedAt = releasedAt, fingerprint = fingerprint),
        )

        val assetContent = content.assetContents.mapKeys { (filename, _) -> "resources/asset/$filename" }
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
        val zipBytes = output.toByteArray()

        val filename = "${command.catalogKey.value}-$version.zip"
        return ExportCatalogZipResult(zipBytes = zipBytes, filename = filename)
    }
}

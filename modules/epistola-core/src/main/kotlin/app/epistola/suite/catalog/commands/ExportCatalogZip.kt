// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.ReleaseInfo
import app.epistola.suite.catalog.CatalogArchiveBuilder
import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.MultipleStencilVersionsInUseException
import app.epistola.suite.catalog.queries.FindStencilVersionExportConflicts
import app.epistola.suite.catalog.queries.GetLatestCatalogRelease
import app.epistola.suite.catalog.revisions.ReleaseContentAssembler
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.time.EpistolaClock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Exports a catalog as a self-contained ZIP archive.
 *
 * Two sources, and the difference matters. With no [version] this exports the **working copy**:
 * content assembled by [CatalogContentBuilder], the same builder fingerprinting uses, so the
 * exported bytes are exactly the fingerprinted bytes — labelled `-dev` when it has drifted from the
 * last release, because it is not any released version.
 *
 * With a [version] it exports **that release, as released**: rebuilt from the content the release
 * retained, carrying the version, timestamp and fingerprint it was cut with, whatever the working
 * copy has done since. Releases cut before content was retained cannot be rebuilt and say so.
 */
data class ExportCatalogZip(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    /** A released version to export as it was released, or null for the working copy. */
    val version: String? = null,
) : Command<ExportCatalogZipResult>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/**
 * Thrown when a release cannot be exported because its content was never retained — every release
 * cut before `V20260923201010`. The working copy is not a substitute: it is whatever the catalog
 * looks like now, which is the thing the caller asked not to be given.
 */
class CatalogReleaseNotRetainedException(catalogKey: CatalogKey, version: String) :
    RuntimeException(
        "Release $version of catalog '${catalogKey.value}' did not retain its content and cannot be exported as released. " +
            "Export the working copy, or cut a new release.",
    )

data class ExportCatalogZipResult(
    val zipBytes: ByteArray,
    val filename: String,
)

@Component
class ExportCatalogZipHandler(
    private val contentBuilder: CatalogContentBuilder,
    private val fingerprintService: CatalogFingerprintService,
    private val archiveBuilder: CatalogArchiveBuilder,
    private val assembler: ReleaseContentAssembler,
) : CommandHandler<ExportCatalogZip, ExportCatalogZipResult> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: ExportCatalogZip): ExportCatalogZipResult {
        command.version?.let { return exportRelease(command, it) }
        return exportWorkingCopy(command)
    }

    /**
     * A release, as released.
     *
     * Neither the stencil-version precheck nor [CatalogFingerprintService.requirePublishable] runs
     * here. Both describe the working copy, and this content is not it: the release passed them
     * when it was cut, it is immutable, and a rule that has tightened since must not retroactively
     * make an already-published release unexportable.
     *
     * The fingerprint is recomputed rather than trusted, and a mismatch refuses the export. It
     * should be impossible — that equality is what `ReleaseRoundTripTest` pins — so if it happens,
     * something has corrupted a revision, and handing over an archive whose contents do not match
     * the fingerprint it advertises would spread that rather than stop it.
     */
    private fun exportRelease(command: ExportCatalogZip, version: String): ExportCatalogZipResult {
        val retained = assembler.assemble(command.tenantKey, command.catalogKey, version)
            ?: throw CatalogReleaseNotRetainedException(command.catalogKey, version)

        val rebuilt = fingerprintService.fingerprint(retained.content)
        val released = retained.release.fingerprint
        if (released != null && rebuilt != released) {
            throw IllegalStateException(
                "Release $version of catalog '${command.catalogKey.value}' rebuilds to fingerprint $rebuilt " +
                    "but was released as $released; its retained content has been altered.",
            )
        }

        return ExportCatalogZipResult(
            zipBytes = archiveBuilder.build(retained.content, retained.release),
            filename = "${command.catalogKey.value}-$version.zip",
        )
    }

    private fun exportWorkingCopy(command: ExportCatalogZip): ExportCatalogZipResult {
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
        val zipBytes = archiveBuilder.build(
            content,
            ReleaseInfo(version = version, releasedAt = releasedAt, fingerprint = fingerprint),
        )

        val filename = "${command.catalogKey.value}-$version.zip"
        return ExportCatalogZipResult(zipBytes = zipBytes, filename = filename)
    }
}

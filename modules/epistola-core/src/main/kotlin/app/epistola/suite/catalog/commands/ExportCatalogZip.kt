// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.ReleaseInfo
import app.epistola.suite.catalog.CatalogArchiveBuilder
import app.epistola.suite.catalog.CatalogBundle
import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.GetLatestCatalogRelease
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.queries.ListReleaseDependencies
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
import tools.jackson.databind.ObjectMapper

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
 *
 * With [includeDependencies] it is a **fat export** ([CatalogBundle]): the archive together with the
 * releases of the catalogs it uses, as pinned (a release's pins; the working copy's locked
 * declarations), and theirs in turn, so it installs where none of them is. Refused while a
 * declaration is not locked to a release yet, and when a pinned release kept no content here.
 */
data class ExportCatalogZip(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    /** A released version to export as it was released, or null for the working copy. */
    val version: String? = null,
    /** Bundle the releases of the catalogs it uses (a fat export). */
    val includeDependencies: Boolean = false,
) : Command<ExportCatalogZipResult>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/** A fat export cannot be made: what it would bundle is not there to bundle, or does not agree. */
class CatalogBundleException(message: String) : RuntimeException(message)

/**
 * Thrown when a release cannot be exported because its content was never retained — every release
 * cut before `V20261018200200`. The working copy is not a substitute: it is whatever the catalog
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
    private val objectMapper: ObjectMapper,
) : CommandHandler<ExportCatalogZip, ExportCatalogZipResult> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: ExportCatalogZip): ExportCatalogZipResult {
        val single = command.version?.let { exportRelease(command, it) } ?: exportWorkingCopy(command)
        if (!command.includeDependencies) return single
        return bundle(command, single)
    }

    /**
     * [single] with the releases of the catalogs it uses, dependencies first. Each catalog appears
     * once: a release already refuses two paths needing different releases of one catalog, so the
     * pins agree; a disagreement here would be a corrupted release and is refused, not guessed at.
     */
    private fun bundle(command: ExportCatalogZip, single: ExportCatalogZipResult): ExportCatalogZipResult {
        val ordered = linkedMapOf<String, CatalogBundle.Entry>()
        val visiting = mutableSetOf<String>()
        fun add(pin: CatalogDependencyDeclaration, by: String) {
            val identity = "${pin.registry}/${pin.namespace}/${pin.catalogKey}"
            val version = pin.releaseVersion
                ?: throw CatalogBundleException("$by uses ${pin.catalogKey}, which is not tied to a release yet. Publish, or resolve its dependencies, first.")
            ordered[identity]?.let { existing ->
                if (existing.version != version) throw CatalogBundleException("${pin.catalogKey} is needed as v${existing.version} and as v$version; a bundle holds one release of each catalog")
                return
            }
            if (!visiting.add(identity)) return
            val key = CatalogKey.of(pin.catalogKey)
            val archive = try {
                exportRelease(ExportCatalogZip(command.tenantKey, key, version), version)
            } catch (e: CatalogReleaseNotRetainedException) {
                throw CatalogBundleException("${pin.catalogKey} v$version kept no content here, so it cannot be bundled.")
            }
            ListReleaseDependencies(command.tenantKey, key, version).query().forEach { add(it, "${pin.catalogKey} v$version") }
            ordered[identity] = CatalogBundle.Entry(pin.registry, pin.namespace, pin.catalogKey, version, pin.releaseFingerprint, archive.zipBytes)
        }
        val catalog = GetCatalog(command.tenantKey, command.catalogKey).query() ?: throw CatalogNotFoundException(command.catalogKey)
        val pins = command.version?.let { ListReleaseDependencies(command.tenantKey, command.catalogKey, it).query() }
            ?: ListCatalogDependencies(command.tenantKey, command.catalogKey).query()
        pins.forEach { add(it, command.catalogKey.value) }
        val label = single.filename.removeSuffix(".zip").removePrefix("${command.catalogKey.value}-")
        val root = CatalogBundle.Entry(catalog.registry, catalog.namespace, command.catalogKey.value, label, null, single.zipBytes)
        return ExportCatalogZipResult(
            zipBytes = CatalogBundle(ordered.values.toList() + root).write(objectMapper),
            filename = "${command.catalogKey.value}-$label-with-dependencies.zip",
        )
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
        // A release recorded before 2.0 names no algorithm; match it rather than compare strings.
        if (released != null && !fingerprintService.matchesFingerprint(retained.content, released)) {
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
        val content = contentBuilder.build(command.tenantKey, command.catalogKey)
        fingerprintService.requirePublishable(content)

        // The emitted fingerprint always describes the actual exported bytes.
        // The version label encodes release state: a clean released version
        // when the working copy matches the latest release, a `-dev`-suffixed
        // label when it drifted (unreleased edits) or was never released.
        // Export is never hard-blocked — `-dev` makes drift unmistakable.
        val fingerprint = fingerprintService.fingerprint(content)
        val catalog = GetCatalog(command.tenantKey, command.catalogKey).query()
            ?: throw CatalogNotFoundException(command.catalogKey)

        // A subscribed catalog has no release of its own to be at: releases belong to whoever
        // authored it, and this installation records which one it installed on the catalog row.
        // Labelling it from `catalog_releases` — which is empty for a subscribed catalog — called a
        // catalog installed at 1.2.0 `0.0.0-dev`.
        //
        // No drift suffix, and deliberately no comparison against `installed_fingerprint`: that is
        // the source's fingerprint over the source's bytes, and installing round-trips content, so
        // comparing a locally built archive against it would report drift that is an artefact of
        // the round trip rather than an edit. Subscribed content cannot be edited anyway —
        // `requireCatalogEditable` refuses.
        if (catalog.type != CatalogType.AUTHORED) {
            val installed = catalog.installedReleaseVersion
            if (installed == null) {
                logger.warn("Exporting subscribed catalog '{}' with no installed version as 0.0.0-dev", command.catalogKey.value)
            }
            val label = installed ?: "0.0.0-dev"
            return ExportCatalogZipResult(
                zipBytes = archiveBuilder.build(
                    content,
                    ReleaseInfo(version = label, releasedAt = catalog.installedAt?.toString(), fingerprint = fingerprint),
                ),
                filename = "${command.catalogKey.value}-$label.zip",
            )
        }

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

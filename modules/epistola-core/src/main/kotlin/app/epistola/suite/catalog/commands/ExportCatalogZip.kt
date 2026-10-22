// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.ReleaseInfo
import app.epistola.suite.catalog.CarriedRelease
import app.epistola.suite.catalog.CatalogArchiveBuilder
import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.queries.ListReleaseDependencies
import app.epistola.suite.catalog.releases.CatalogReleaseNotFoundException
import app.epistola.suite.catalog.revisions.ReleaseContentAssembler
import app.epistola.suite.catalog.revisions.RetainedRelease
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
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
 * Only released versions are exported (contract 2.0): [version], or the catalog's highest release.
 * A tenant snapshot alone exports the current version's content ([workingCopy]).
 *
 * With a [version] it exports **that release, as released**: rebuilt from the content the release
 * retained, carrying the version, timestamp and fingerprint it was cut with, whatever the working
 * copy has done since.
 *
 * With [includeDependencies] it is a **fat export** ([CatalogBundle]): the archive together with the
 * releases of the catalogs it uses, as pinned (a release's pins; the working copy's locked
 * declarations), and theirs in turn, so it installs where none of them is. Refused while a
 * declaration is not locked to a release yet, and when a pinned release is not here.
 */
data class ExportCatalogZip(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    /** A released version to export as it was released, or null for the catalog's highest release. */
    val version: String? = null,
    /** Bundle the releases of the catalogs it uses (a fat export). */
    val includeDependencies: Boolean = false,
    /**
     * Export the current version's content rather than a release: what a tenant snapshot carries
     * to restore drafts as they are. Not a release, so not something an import installs (contract
     * 2.0: only released versions are exported). Internal so no API can set it.
     */
    internal val workingCopy: Boolean = false,
) : Command<ExportCatalogZipResult>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/**
 * The system catalog is not exported. Every installation has it, from the suite itself, and what it
 * keeps (its seeded fonts with their bundled faces) is more than the archive its fingerprint covers.
 */
class SystemCatalogNotExportableException : RuntimeException("The system catalog is not exported: every installation has it, from the suite itself.")

/** A fat export cannot be made: what it would bundle is not there to bundle, or does not agree. */
class CatalogBundleException(message: String) : RuntimeException(message)

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
        if (command.catalogKey == SYSTEM_CATALOG_KEY) throw SystemCatalogNotExportableException()
        if (command.workingCopy) return exportWorkingCopy(command)
        val version = command.version ?: highestRelease(command)
        if (!command.includeDependencies) return exportRelease(command, version)
        return withDependencies(command, version)
    }

    /**
     * Release [version] carrying the releases it pins, transitively, at `dependencies/…` (contract
     * `importCatalog`, wire v8), each before every release that pins it, each once. Its manifest and
     * fingerprint are the same as without them.
     *
     * The bundled system catalog is not carried: every installation has it, and a pin the archive
     * does not carry is resolved from what the installation has.
     */
    private fun withDependencies(command: ExportCatalogZip, version: String): ExportCatalogZipResult {
        val ordered = linkedMapOf<String, CarriedRelease>()
        val pinnedAt = mutableMapOf<String, String>()
        val visiting = mutableSetOf<String>()
        fun add(pin: CatalogDependencyDeclaration, by: String) {
            if (pin.catalogKey == SYSTEM_CATALOG_KEY.value) return
            val identity = "${pin.registry}/${pin.namespace}/${pin.catalogKey}"
            val pinned = pin.releaseVersion
                ?: throw CatalogBundleException("$by uses ${pin.catalogKey}, which it does not pin to a release yet.")
            pinnedAt[identity]?.let { existing ->
                if (existing != pinned) throw CatalogBundleException("${pin.catalogKey} is pinned as v$existing and as v$pinned; an archive carries one release of each catalog")
                return
            }
            if (!visiting.add(identity)) return
            pinnedAt[identity] = pinned
            val key = CatalogKey.of(pin.catalogKey)
            val retained = try {
                retainedRelease(command.tenantKey, key, pinned)
            } catch (e: CatalogReleaseNotFoundException) {
                throw CatalogBundleException("${pin.catalogKey} v$pinned is not here, so it cannot be carried.")
            }
            ListReleaseDependencies(command.tenantKey, key, pinned).query().forEach { add(it, "${pin.catalogKey} v$pinned") }
            ordered[identity] = CarriedRelease(pin.registry, pin.namespace, pin.catalogKey, retained.content, retained.release)
        }
        ListReleaseDependencies(command.tenantKey, command.catalogKey, version).query().forEach { add(it, command.catalogKey.value) }
        val root = retainedRelease(command.tenantKey, command.catalogKey, version)
        return ExportCatalogZipResult(
            zipBytes = archiveBuilder.build(root.content, root.release, ordered.values.toList()),
            filename = "${command.catalogKey.value}-$version-with-dependencies.zip",
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
        val retained = retainedRelease(command.tenantKey, command.catalogKey, version)
        return ExportCatalogZipResult(
            zipBytes = archiveBuilder.build(retained.content, retained.release),
            filename = "${command.catalogKey.value}-$version.zip",
        )
    }

    /** Release [version] of [catalogKey] as it retained it, its fingerprint checked. */
    private fun retainedRelease(tenantKey: TenantKey, catalogKey: CatalogKey, version: String): RetainedRelease {
        val retained = assembler.assemble(tenantKey, catalogKey, version)
            ?: throw CatalogReleaseNotFoundException(catalogKey, version)

        val rebuilt = fingerprintService.fingerprint(retained.content)
        val released = retained.release.fingerprint
        // A release recorded before 2.0 names no algorithm; match it rather than compare strings.
        if (released != null && !fingerprintService.matchesFingerprint(retained.content, released)) {
            throw IllegalStateException(
                "Release $version of catalog '${catalogKey.value}' rebuilds to fingerprint $rebuilt " +
                    "but was released as $released; its retained content has been altered.",
            )
        }
        return retained
    }

    /**
     * The current version's content, for a tenant snapshot. Wire v8 numbers every archive's release
     * `MAJOR.MINOR.PATCH`, so it is labelled by the catalog's highest release, released here or
     * received, or `0.0.0` without one; the fingerprint describes exactly what it holds, which is not
     * necessarily that release.
     */
    private fun exportWorkingCopy(command: ExportCatalogZip): ExportCatalogZipResult {
        val content = contentBuilder.build(command.tenantKey, command.catalogKey)
        fingerprintService.requirePublishable(content)
        val fingerprint = fingerprintService.fingerprint(content)
        val catalog = GetCatalog(command.tenantKey, command.catalogKey).query()
            ?: throw CatalogNotFoundException(command.catalogKey)
        val version = catalog.highestReleaseVersion ?: UNRELEASED
        // When it was received, for a release received from elsewhere; a release made here is not dated.
        val releasedAt = catalog.installedAt?.takeIf { version == catalog.installedReleaseVersion }?.toString()
        val zipBytes = archiveBuilder.build(
            content,
            ReleaseInfo(version = version, releasedAt = releasedAt, fingerprint = fingerprint),
        )

        val filename = "${command.catalogKey.value}-$version.zip"
        return ExportCatalogZipResult(zipBytes = zipBytes, filename = filename)
    }

    private companion object {
        /** The label of a working copy with no release to be on. */
        const val UNRELEASED = "0.0.0"
    }

    /** The catalog's highest release, released here or received (ADR 0032). */
    private fun highestRelease(command: ExportCatalogZip): String {
        val catalog = GetCatalog(command.tenantKey, command.catalogKey).query() ?: throw CatalogNotFoundException(command.catalogKey)
        return catalog.highestReleaseVersion ?: throw CatalogReleaseNotFoundException(command.catalogKey, "release")
    }
}

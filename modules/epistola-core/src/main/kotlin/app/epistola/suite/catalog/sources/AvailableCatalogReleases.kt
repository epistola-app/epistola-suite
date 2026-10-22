// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.sources

import app.epistola.catalog.archive.CatalogArchivePolicy
import app.epistola.catalog.archive.CatalogArchiveReader
import app.epistola.catalog.migration.CatalogMigrationOptions
import app.epistola.suite.catalog.CatalogIdentityProperties
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.CatalogSizeLimits
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.commands.CatalogImportDependenciesMissingException
import app.epistola.suite.catalog.commands.ImportCatalogZipResult
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.GetCatalogReleaseDetail
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.catalog.releases.CatalogReleaseNotFoundException
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.environments.MissingDependencyReason
import app.epistola.suite.environments.MissingReleaseDependency
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream

/**
 * The releases a subscribed catalog's source offers that the tenant has not installed (contract
 * `listAvailableCatalogReleases`), lowest first, older lines included: a fix `1.4.1` is listed while
 * `2.0.0` is installed. Each is compared to the closest installed release numbered below it, with
 * the resources it added, modified or removed against that one and whether the releases it pins
 * are here. Nothing is installed by reading it; the source is asked, so it may fail with
 * [CatalogSourceUnavailableException].
 */
data class ListAvailableCatalogReleases(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<AvailableCatalogReleases>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

data class AvailableCatalogReleases(val registry: String, val namespace: String, val catalogKey: CatalogKey, val available: List<AvailableRelease>)

/**
 * @property comparedTo the closest installed release numbered below it; null when there is none,
 *   and then everything it holds is [ResourceChangeKind.ADDED].
 */
data class AvailableRelease(
    val releaseVersion: String,
    val comparedTo: String?,
    val changes: List<AvailableReleaseChange>,
    val dependencies: List<AvailableReleaseDependency>,
)

enum class ResourceChangeKind { ADDED, MODIFIED, REMOVED }

/** A resource an available release changed, by `type` and `key`, and its name there. */
data class AvailableReleaseChange(val type: String, val key: String, val name: String?, val change: ResourceChangeKind)

/** A release another catalog's available release pins, and whether the tenant has it. */
data class AvailableReleaseDependency(val registry: String, val namespace: String, val catalogKey: String, val releaseVersion: String, val present: Boolean)

/** A catalog that has no source to ask: not subscribed, or imported from an archive. */
class CatalogHasNoSourceException(val catalogKey: CatalogKey, reason: String) : RuntimeException("Catalog '${catalogKey.value}' $reason")

@Component
class ListAvailableCatalogReleasesHandler(
    private val sources: CatalogReleaseSources,
    private val archives: AvailableReleaseArchives,
) : QueryHandler<ListAvailableCatalogReleases, AvailableCatalogReleases> {
    override fun handle(query: ListAvailableCatalogReleases): AvailableCatalogReleases {
        val catalog = GetCatalog(query.tenantKey, query.catalogKey).query() ?: throw CatalogNotFoundException(query.catalogKey)
        val source = sources.sourceOf(catalog)
        val installed = ListCatalogReleaseSummaries(query.tenantKey, query.catalogKey).query().map { it.version }.toSet()
        val available = source.offered(catalog).filter { it !in installed }
            .sortedWith(compareBy(nullsLast()) { SemVer.parseOrNull(it) })
            .map { version -> archives.describe(catalog.tenantKey, catalog.id, version, source.archive(catalog, version), installed) }
        return AvailableCatalogReleases(catalog.registry, catalog.namespace, catalog.id, available)
    }
}

/**
 * Installs release [releaseVersion] of a subscribed catalog from its source, beside its other
 * releases (contract `installCatalogRelease`): nothing is updated in place and nothing is removed.
 * The releases it pins that the tenant lacks are installed first, from the source serving their
 * registry; one none can supply refuses it ([CatalogImportDependenciesMissingException]). The same
 * release again changes nothing (`newlyInstalled` false); the same number with other content is
 * refused, as importing it is.
 */
data class InstallCatalogRelease(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val releaseVersion: String,
) : Command<ImportCatalogZipResult>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_MANAGE
}

@Component
class InstallCatalogReleaseHandler(
    private val sources: CatalogReleaseSources,
    private val archives: AvailableReleaseArchives,
) : CommandHandler<InstallCatalogRelease, ImportCatalogZipResult> {
    override fun handle(command: InstallCatalogRelease): ImportCatalogZipResult {
        val catalog = GetCatalog(command.tenantKey, command.catalogKey).query() ?: throw CatalogNotFoundException(command.catalogKey)
        val source = sources.sourceOf(catalog)
        val version = command.releaseVersion.trim()
        if (version !in source.offered(catalog)) throw CatalogReleaseNotFoundException(command.catalogKey, version)
        // The releases it pins that are not here, first, each from the source serving its registry.
        val missing = archives.pins(source.archive(catalog, version)).filter { !archives.present(command.tenantKey, it) }
        val unavailable = missing.filterNot { sources.installByName(command.tenantKey, it.registry, it.namespace, it.catalogKey, requireNotNull(it.releaseVersion)) }
        if (unavailable.isNotEmpty()) {
            throw CatalogImportDependenciesMissingException(
                command.catalogKey,
                version,
                unavailable.map { MissingReleaseDependency(it.registry, it.namespace, it.catalogKey, it.releaseVersion!!, MissingDependencyReason.UNAVAILABLE) },
            )
        }
        return source.install(catalog, version)
    }
}

/** The source of a catalog, or the refusal the contract names for one without (made here, or imported from an archive). */
private fun CatalogReleaseSources.sourceOf(catalog: app.epistola.suite.catalog.Catalog): CatalogReleaseSource = of(catalog)
    ?: throw CatalogHasNoSourceException(catalog.id, "was imported from an archive and has no source to ask")

/** Reads what an available release's archive holds, against what the tenant has installed. */
@Component
class AvailableReleaseArchives(
    private val jdbi: Jdbi,
    private val sizeLimits: CatalogSizeLimits,
    private val identity: CatalogIdentityProperties,
    private val dependencyStore: CatalogDependencyStore,
    private val assembler: app.epistola.suite.catalog.revisions.ReleaseContentAssembler,
    private val fingerprints: app.epistola.suite.catalog.CatalogFingerprintService,
) {

    /** [bytes], release [version], against the closest of [installed] below it. */
    fun describe(tenantKey: TenantKey, catalogKey: CatalogKey, version: String, bytes: ByteArray, installed: Set<String>): AvailableRelease {
        val archive = read(tenantKey, bytes)
        val manifest = archive.manifest
        // Both sides by the function every comparison uses: the archive as it came, the installed
        // release from the content it retained.
        val incoming = fingerprints.resourceFingerprints(archive)
        val number = SemVer.parseOrNull(version)
        val comparedTo = installed.mapNotNull { v -> SemVer.parseOrNull(v)?.let { v to it } }
            .filter { number != null && it.second < number }
            .maxByOrNull { it.second }?.first
        val baseline = comparedTo?.let { GetCatalogReleaseDetail(tenantKey, catalogKey, it).query() }?.resources.orEmpty()
        val before = comparedTo?.let { assembler.assemble(tenantKey, catalogKey, it) }?.let { fingerprints.resourceFingerprints(it.content) }.orEmpty()
        val names = manifest.resources.associate { "${it.type}/${it.key}" to it.name }
        val changes = buildList {
            for ((key, fingerprint) in incoming.toSortedMap()) {
                when (before[key]) {
                    null -> add(change(key, names[key], ResourceChangeKind.ADDED))
                    fingerprint -> {}
                    else -> add(change(key, names[key], ResourceChangeKind.MODIFIED))
                }
            }
            baseline.filter { it.contentKey !in incoming }.sortedBy { it.contentKey }.forEach { add(change(it.contentKey, it.name, ResourceChangeKind.REMOVED)) }
        }
        val dependencies = manifest.catalogDependencies.orEmpty().map(CatalogDependencyDeclaration::of).filter { it.pinned }.map {
            AvailableReleaseDependency(it.registry, it.namespace, it.catalogKey, it.releaseVersion!!, present(tenantKey, it))
        }
        return AvailableRelease(version, comparedTo, changes, dependencies)
    }

    /** The releases [bytes] pins. */
    fun pins(bytes: ByteArray): List<CatalogDependencyDeclaration> = read(null, bytes).manifest.catalogDependencies.orEmpty()
        .map(CatalogDependencyDeclaration::of).filter { it.pinned }

    /** Whether the tenant has the release [pin] names, with its fingerprint. */
    fun present(tenantKey: TenantKey, pin: CatalogDependencyDeclaration): Boolean = jdbi.withHandle<Boolean, Exception> { dependencyStore.pinHolds(it, tenantKey, pin) }

    private fun read(tenantKey: TenantKey?, bytes: ByteArray) = CatalogArchiveReader.read(
        ByteArrayInputStream(bytes),
        CatalogArchivePolicy(maxCompressedBytes = sizeLimits.maxZipSize.toBytes(), maxExpandedBytes = sizeLimits.maxDecompressedSize.toBytes()),
        CatalogMigrationOptions(namespace = CatalogIdentityProperties.defaultNamespace(tenantKey ?: TenantKey.of("unknown")), registry = identity.defaultRegistry),
    ).archive ?: throw CatalogSourceUnavailableException("The catalog's source answered with something that is not a valid catalog archive", badGateway = true)

    private fun change(key: String, name: String?, kind: ResourceChangeKind) = AvailableReleaseChange(key.substringBefore('/'), key.substringAfter('/'), name, kind)
}

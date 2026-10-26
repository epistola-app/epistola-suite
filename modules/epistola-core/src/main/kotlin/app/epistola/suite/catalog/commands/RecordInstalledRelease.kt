// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.canonical.QualifiedCatalogFingerprint
import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.FontVariantEntry
import app.epistola.catalog.protocol.ReleaseInfo
import app.epistola.generation.pdf.RenderingDefaults
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.catalog.CatalogContent
import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.revisions.ReleaseDescription
import app.epistola.suite.catalog.revisions.ReleaseResourceStore
import app.epistola.suite.catalog.revisions.ResourceContentStore
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.SystemInternal
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * Records the installed version of a catalog (a release it received) as a release of it, with the
 * content that install put in place.
 *
 * Generation renders releases, so a received release needs one: it arrives as content put in place
 * in the next version, and this is what turns that into a release that can be rendered.
 * The release carries the publisher's version and fingerprint, not a recomputation over the mirror,
 * so it is recognisably the publisher's release; only a legacy fingerprint, which names no algorithm,
 * is replaced by the qualified one of the installed content.
 *
 * Idempotent: a release already recorded for the installed version is left alone. Does nothing for
 * a catalog with no installed version (one only released here, ADR 0032), unless [release] names one.
 *
 * `SystemInternal`: dispatched by the install, upgrade and import commands once their own
 * authorization has passed, and by the bundled-catalog bootstrap, which runs as the system.
 *
 * @return the version recorded or already present, or null when there is nothing to record.
 */
data class RecordInstalledRelease(
    val catalog: CatalogId,
    /**
     * The release's content as its archive carries it, when the caller has the archive: retained
     * instead of a rebuild from the installed rows, so the release exports with its publisher's
     * fingerprint. Null rebuilds it from what is installed (an upgrade from 1.x, the system catalog's
     * seeded fonts).
     */
    val published: CatalogContent? = null,
    /**
     * A release recorded beside the installed one, with [published] as its content (a release
     * installed below the highest): its number and its publisher's fingerprint. Null records the
     * installed release.
     */
    val release: RecordedRelease? = null,
    /**
     * The release as its archive describes it, when the caller has it: its id, its metadata and the
     * release it was made from are kept ([ReceivedReleases]). Null describes it as this installation
     * records it now: a new id, the current rendering defaults, no base.
     */
    val received: ReleaseInfo? = null,
) : Command<String?>,
    SystemInternal {
    val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
}

/** A release's number and its publisher's fingerprint, when the archive carries one. */
data class RecordedRelease(val version: String, val fingerprint: String?)

/**
 * Lets the bundled-catalog bootstrap record a catalog's release itself, after seeding what its
 * manifest does not carry (the system catalog's fonts), instead of at the end of each install step.
 */
object InstalledReleaseRecording {
    private val deferred: ScopedValue<Boolean> = ScopedValue.newInstance()

    val isDeferred: Boolean get() = deferred.isBound && deferred.get()

    fun <T> deferred(block: () -> T): T = ScopedValue.where(deferred, true).call<T, RuntimeException>(block)
}

/**
 * Hands the content an install fetched to the upgrade that runs it, so the upgrade records the release
 * it moves to as its archive holds it ([RecordInstalledRelease.published]), as a fresh install does.
 * The install writes the content in the form the suite stores (a stencil instance's own-catalog
 * qualifier dropped, defaults written out), and a release rebuilt from those rows can hash to another
 * content hash than the one it is named by, so it would not be the release its source published.
 */
object InstalledContentHandoff {
    private val slot: ScopedValue<java.util.concurrent.atomic.AtomicReference<CatalogContent?>> = ScopedValue.newInstance()

    /** Runs [install] and returns its result with the content it fetched, when it fetched all of it. */
    fun <T> capturing(install: () -> T): Pair<T, CatalogContent?> {
        val fetched = java.util.concurrent.atomic.AtomicReference<CatalogContent?>()
        val result = ScopedValue.where(slot, fetched).call<T, RuntimeException>(install)
        return result to fetched.get()
    }

    /** Offers [content], as the install fetched it, to the upgrade running the install, if one is. */
    fun offer(content: CatalogContent?) {
        if (slot.isBound) slot.get().set(content)
    }
}

@Component
class RecordInstalledReleaseHandler(
    private val jdbi: Jdbi,
    private val contentBuilder: CatalogContentBuilder,
    private val fingerprintService: CatalogFingerprintService,
    private val revisionStore: ResourceContentStore,
    private val releaseResourceStore: ReleaseResourceStore,
    private val objectMapper: ObjectMapper,
    private val dependencyStore: CatalogDependencyStore,
    private val rows: CatalogImportRows,
    private val nextVersionChanges: app.epistola.suite.catalog.releases.NextVersionChanges,
    private val receivedReleases: ReceivedReleases,
) : CommandHandler<RecordInstalledRelease, String?> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: RecordInstalledRelease): String? {
        val catalog = GetCatalog(command.catalog).query() ?: return null
        val version = command.release?.version ?: catalog.installedReleaseVersion ?: return null
        check(command.release == null || command.published != null) { "A release beside the installed one is recorded from its archive" }

        val exists = jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery("SELECT EXISTS (SELECT 1 FROM catalog_versions WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :v)")
                .bind("t", command.tenantKey)
                .bindCatalog("c", command.catalog)
                .bind("v", version)
                .mapTo(Boolean::class.java)
                .one()
        }
        // The installed version carries the archive's theme settings (contract ADR 0007), so its
        // release, rebuilt or not, renders with its default theme. Before the baseline: they are the
        // release's, not changes of the next version's own.
        if (!exists && command.release == null) command.published?.let { rows.updateThemeSettings(command.catalog, it.catalog, objectMapper) }
        // The installed release is in place in the next version: what it holds now is the baseline
        // its own changes are measured from (ADR 0032).
        if (command.release == null) nextVersionChanges.recordBaseline(command.catalog)
        if (exists) return version
        val received = command.received?.takeIf { it.version == version }
        val renderingDefaults = received?.let { receivedReleases.renderingDefaultsVersion(command.catalogKey, it) } ?: RenderingDefaults.CURRENT.version

        // What the release keeps, and so what everything recorded of it is computed over: each entry's
        // fingerprint, and a fingerprint or content hash computed here. A font's faces are its own,
        // bundled ones included (ADR 0033).
        val content = command.published ?: contentBuilder.build(command.catalog)
        // The publisher's fingerprint when it names its algorithm. A legacy one (a catalog from wire v7
        // or earlier, or installed under 1.x) cannot be checked against this wire-v8 content, and a
        // release whose fingerprint names no algorithm cannot be locked to or pinned by a dependent
        // (contract ADR 0003), so such a release records the qualified fingerprint of what it holds.
        val publisherFingerprint = if (command.release != null) command.release.fingerprint else catalog.installedFingerprint
        val fingerprint = publisherFingerprint?.takeIf { QualifiedCatalogFingerprint.parse(it) != null }
            ?: fingerprintService.fingerprint(content)
        // Its identity (ADR 0034): a V6 fingerprint is its content hash, verified on receipt; a release
        // received with an older one is hashed from the content it brings.
        val contentHash = fingerprint.takeIf(app.epistola.suite.catalog.CatalogCanonicalizer::isContentHash) ?: fingerprintService.contentHash(content)
        // `release_resources.fingerprint` keeps the V3 form, of what the release holds. The installed
        // baseline is of another function (the complete form), so it is not mixed in.
        val resourceFingerprints = fingerprintService.perResourceFingerprints(content)

        jdbi.useTransaction<Exception> { handle ->
            val releasedAt = handle.createQuery("SELECT NOW()").mapTo(OffsetDateTime::class.java).one()
            // A received release keeps the id it has everywhere; else it is chosen here rather than by
            // the column's default, so its snapshot says it too.
            received?.let { receivedReleases.requireIdFree(handle, command.catalog, it) }
            val releaseId = received?.releaseId ?: handle.createQuery("SELECT epistola_version_key()").mapTo(String::class.java).one()
            val manifest = content.toManifest(
                ReleaseInfo(
                    version = version,
                    releasedAt = releasedAt.toString(),
                    fingerprint = fingerprint,
                    releaseId = releaseId,
                    // Every namespace it came with, kept as it came; this suite's names what it renders with.
                    metadata = ReleaseDescription.metadata(renderingDefaults, received?.metadata),
                    base = received?.base,
                ),
            )

            handle.createUpdate(
                """
                INSERT INTO catalog_versions (tenant_key, catalog_id, version_key, state, origin, version, fingerprint, content_hash, notes,
                                              manifest_snapshot, released_at, rendering_defaults_version, base_version, base_version_id,
                                              base_fingerprint, released_by_installation, created_at)
                SELECT :t, c.catalog_id, :releaseId, 'released',
                       -- Received, unless it names this installation as the one that cut it (a release
                       -- that went out and came back is this installation's own).
                       CASE WHEN CAST(:releasedByInstallation AS UUID) IS NOT DISTINCT FROM (
                                SELECT CAST(m.value ->> 'id' AS UUID) FROM app_metadata m WHERE m.key = 'installation')
                            THEN 'authored' ELSE 'installed' END,
                       :version, :fingerprint, :contentHash, :notes, CAST(:snapshot AS JSONB), :releasedAt, :renderingDefaults,
                       :baseVersion,
                       (SELECT b.version_id FROM catalog_versions b
                        WHERE b.tenant_key = :t AND b.catalog_id = c.catalog_id AND b.version = :baseVersion AND b.state = 'released'),
                       :baseFingerprint, CAST(:releasedByInstallation AS UUID), :releasedAt
                FROM catalogs c WHERE c.tenant_key = :t AND c.catalog_id = ${catalogAt("t", "c")}
                """,
            )
                .bind("contentHash", contentHash)
                // Received: the installation that cut it, as the release names it; nobody here released it.
                .bind("releasedByInstallation", ReleaseDescription.releasedByInstallation(received?.metadata))
                .bind("t", command.tenantKey)
                .bindCatalog("c", command.catalog)
                .bind("version", version)
                .bind("fingerprint", fingerprint)
                .bind("notes", "Installed from ${catalog.sourceUrl ?: "a catalog archive"}")
                .bind("snapshot", objectMapper.writeValueAsString(manifest))
                .bind("releasedAt", releasedAt)
                .bind("renderingDefaults", renderingDefaults)
                .bind("releaseId", releaseId)
                // The release it was made from, as it named it: its lineage, to find a merge base by.
                .bind("baseVersion", received?.base?.version)
                .bind("baseFingerprint", received?.base?.fingerprint)
                .execute()
            // The releases merged into it where it was cut, as it names them: its line contains them.
            app.epistola.suite.catalog.releases.ReleaseLineage.recordMerged(handle, command.catalog, version, ReleaseDescription.merged(received?.metadata))

            // The publisher's pins, as installed: a received release's declarations are its archive's.
            dependencyStore.recordRelease(
                handle,
                command.catalog,
                version,
                content.catalogDependencies.orEmpty().map(CatalogDependencyDeclaration::of),
            )
            val retained = revisionStore.retain(handle, command.tenantKey, content)
            // A release beside the installed one may hold resources no version of the catalog has.
            releaseResourceStore.record(handle, command.catalog, version, content, retained.contents, resourceFingerprints, registerNew = command.release != null, variants = retained.variants)
            // Its settings, frozen on its row (ADR 0036), as its manifest states them: resolved now its
            // declarations and its themes are recorded.
            handle.createUpdate(
                """
                UPDATE catalog_versions
                SET default_theme_resource_id = catalog_release_default_theme(tenant_key, catalog_id, version, manifest_snapshot),
                    presets = catalog_release_presets(manifest_snapshot)
                WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :version AND state = 'released'
                """,
            )
                .bind("t", command.tenantKey)
                .bindCatalog("c", command.catalog)
                .bind("version", version)
                .execute()
        }

        logger.info("Recorded installed release {}@{} for tenant {}", command.catalogKey.value, version, command.tenantKey.value)
        return version
    }
}

/**
 * A release's content as its publisher released it: [manifest], every resource as the archive binds
 * it ([bound], by `type/key`), and the binaries it carries ([binaries], by archive path). A rebuild
 * from the rows an install mirrors it into writes every default out (an absent list as empty, an
 * absent sort order as 0): the same content, but no longer the publisher's fingerprint.
 */
internal fun publishedContent(
    manifest: app.epistola.catalog.protocol.CatalogManifest,
    bound: Map<String, app.epistola.catalog.protocol.CatalogResource>,
    binaries: Map<String, ByteArray>,
): CatalogContent {
    val details = bound.mapValues { (_, resource) -> app.epistola.catalog.protocol.ResourceDetail(app.epistola.suite.catalog.CATALOG_SCHEMA_VERSION, resource) }
    val paths = details.values.flatMap { detail ->
        when (val resource = detail.resource) {
            is app.epistola.catalog.protocol.ImageResource -> listOf(resource.contentPath())
            is app.epistola.catalog.protocol.FontResource -> resource.variants.map { it.contentPath() }
            else -> emptyList()
        }
    }
    val byPath = binaries.mapKeys { it.key.removePrefix("./") }
    return CatalogContent(
        catalog = manifest.catalog,
        resourceEntries = manifest.resources,
        resourceDetails = details,
        dependencies = manifest.dependencies,
        catalogDependencies = manifest.catalogDependencies,
        assetContents = paths.distinct().mapNotNull { path -> byPath[path]?.let { path to it } }.toMap(),
        publisher = manifest.publisher,
    )
}

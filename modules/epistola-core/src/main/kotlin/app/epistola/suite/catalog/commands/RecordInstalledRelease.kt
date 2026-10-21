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
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.revisions.ReleaseDescription
import app.epistola.suite.catalog.revisions.ReleaseEntryStore
import app.epistola.suite.catalog.revisions.ResourceRevisionStore
import app.epistola.suite.catalog.versionAt
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
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
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
    SystemInternal

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

@Component
class RecordInstalledReleaseHandler(
    private val jdbi: Jdbi,
    private val contentBuilder: CatalogContentBuilder,
    private val fingerprintService: CatalogFingerprintService,
    private val revisionStore: ResourceRevisionStore,
    private val releaseEntryStore: ReleaseEntryStore,
    private val objectMapper: ObjectMapper,
    private val dependencyStore: CatalogDependencyStore,
    private val rows: CatalogImportRows,
    private val nextVersionChanges: app.epistola.suite.catalog.versions.NextVersionChanges,
    private val receivedReleases: ReceivedReleases,
) : CommandHandler<RecordInstalledRelease, String?> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: RecordInstalledRelease): String? {
        val catalog = GetCatalog(command.tenantKey, command.catalogKey).query() ?: return null
        val version = command.release?.version ?: catalog.installedReleaseVersion ?: return null
        check(command.release == null || command.published != null) { "A release beside the installed one is recorded from its archive" }

        val exists = jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery("SELECT EXISTS (SELECT 1 FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :v)")
                .bind("t", command.tenantKey)
                .bind("c", command.catalogKey)
                .bind("v", version)
                .mapTo(Boolean::class.java)
                .one()
        }
        // The installed release is in place in the next version: what it holds now is the baseline
        // its own changes are measured from (ADR 0032).
        if (command.release == null) nextVersionChanges.recordBaseline(command.tenantKey, command.catalogKey)
        if (exists) return version
        val received = command.received?.takeIf { it.version == version }
        val renderingDefaults = received?.let { receivedReleases.renderingDefaultsVersion(command.catalogKey, it) } ?: RenderingDefaults.CURRENT.version

        // The installed version carries the archive's theme settings (contract ADR 0007), so its
        // release, rebuilt or not, renders with its default theme.
        if (command.release == null) command.published?.let { rows.updateThemeSettings(command.tenantKey, command.catalogKey, it.catalog, objectMapper) }
        val content = command.published ?: contentBuilder.build(command.tenantKey, command.catalogKey)
        // The publisher's fingerprint when it names its algorithm. A legacy one (a catalog from wire v7
        // or earlier, or installed under 1.x) cannot be checked against this wire-v8 content, and a
        // release whose fingerprint names no algorithm cannot be locked to or pinned by a dependent
        // (contract ADR 0003), so such a release records the qualified fingerprint of what it holds.
        val publisherFingerprint = if (command.release != null) command.release.fingerprint else catalog.installedFingerprint
        val fingerprint = publisherFingerprint?.takeIf { QualifiedCatalogFingerprint.parse(it) != null }
            ?: fingerprintService.fingerprint(content)
        // `release_entries.fingerprint` keeps the V3 form, of what the release holds. The installed
        // baseline is of another function (the complete form), so it is not mixed in.
        val resourceFingerprints = fingerprintService.perResourceFingerprints(content)

        jdbi.useTransaction<Exception> { handle ->
            val releasedAt = handle.createQuery("SELECT NOW()").mapTo(OffsetDateTime::class.java).one()
            // A received release keeps the id it has everywhere; else it is chosen here rather than by
            // the column's default, so its snapshot says it too.
            received?.let { receivedReleases.requireIdFree(handle, command.tenantKey, command.catalogKey, it) }
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
                INSERT INTO catalog_releases (tenant_key, catalog_id, version, fingerprint, notes, manifest_snapshot, released_at,
                                              rendering_defaults_version, version_key, base_version, base_fingerprint, released_by_installation)
                VALUES (:t, ${catalogAt("t", "c")}, :version, :fingerprint, :notes, CAST(:snapshot AS JSONB), :releasedAt, :renderingDefaults, :releaseId,
                        :baseVersion, :baseFingerprint, :releasedByInstallation)
                """,
            )
                // Received: the installation that cut it, as the release names it; nobody here released it.
                .bind("releasedByInstallation", ReleaseDescription.releasedByInstallation(received?.metadata))
                .bind("t", command.tenantKey)
                .bind("c", command.catalogKey)
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

            // The publisher's pins, as installed: a received release's declarations are its archive's.
            dependencyStore.recordRelease(
                handle,
                command.tenantKey,
                command.catalogKey,
                version,
                content.catalogDependencies.orEmpty().map(CatalogDependencyDeclaration::of),
            )
            val revisionDigests = revisionStore.retain(handle, command.tenantKey, withBundledFaces(handle, command.tenantKey, command.catalogKey, content))
            // A release beside the installed one may hold resources no version of the catalog has.
            releaseEntryStore.record(handle, command.tenantKey, command.catalogKey, version, content, revisionDigests, resourceFingerprints, registerNew = command.release != null)
        }

        logger.info("Recorded installed release {}@{} for tenant {}", command.catalogKey.value, version, command.tenantKey.value)
        return version
    }
}

/**
 * [content] with the bundled faces of its fonts, for retaining it.
 *
 * The wire form carries only asset-backed faces, so content built from the installed rows lists a
 * seeded font (the system catalog's) without any. A release keeps them by hash instead: rendering
 * reads the face from the retained revision and its bytes from the classpath, checked against that
 * hash (`ReleaseContentAssembler.readBinary`). Without them a release renders the font as Helvetica.
 */
internal fun withBundledFaces(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, content: CatalogContent): CatalogContent {
    data class BundledFace(val slug: String, val weight: Int, val italic: Boolean, val location: String, val contentHash: String)
    val faces = handle.createQuery(
        """
        SELECT f.slug, v.weight, v.italic, v.classpath_location, v.content_hash
        FROM font_variants v
        JOIN fonts f ON f.tenant_key = v.tenant_key AND f.version_id = v.version_id AND f.resource_id = v.font_resource_id
        WHERE v.tenant_key = :t AND v.version_id = ${versionAt("t", "c")}
          AND v.source = 'CLASSPATH' AND v.content_hash IS NOT NULL
        ORDER BY f.slug, v.italic, v.weight
        """,
    )
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .map { rs, _ -> BundledFace(rs.getString("slug"), rs.getInt("weight"), rs.getBoolean("italic"), rs.getString("classpath_location"), rs.getString("content_hash")) }
        .list()
        .groupBy { it.slug }
    if (faces.isEmpty()) return content

    val details = content.resourceDetails.mapValues { (_, detail) ->
        val font = detail.resource as? FontResource ?: return@mapValues detail
        val bundled = faces[font.key].orEmpty().filter { face -> font.variants.none { it.weight == face.weight && it.italic == face.italic } }
        if (bundled.isEmpty()) return@mapValues detail
        val added = bundled.map { face ->
            val mediaType = if (face.location.endsWith(".otf", ignoreCase = true)) AssetMediaType.OTF else AssetMediaType.TTF
            FontVariantEntry(weight = face.weight, italic = face.italic, mediaType = mediaType.mimeType, contentHash = face.contentHash)
        }
        detail.copy(resource = font.copy(variants = font.variants + added))
    }
    return content.copy(resourceDetails = details)
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

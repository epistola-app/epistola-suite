// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.ReleaseInfo
import app.epistola.generation.pdf.RenderingDefaults
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogArchiveBuilder
import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.CatalogReleasePublicationPort
import app.epistola.suite.catalog.CatalogReleasePublicationRequest
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.ContentSource
import app.epistola.suite.catalog.ReleaseStencilCheck
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.GetCatalogResourceChanges
import app.epistola.suite.catalog.revisions.ReleaseEntryStore
import app.epistola.suite.catalog.revisions.ResourceRevisionStore
import app.epistola.suite.catalog.versions.CatalogDraft
import app.epistola.suite.catalog.versions.CatalogDrafts
import app.epistola.suite.catalog.versions.CatalogVersionBehindException
import app.epistola.suite.catalog.versions.CatalogVersionNotFoundException
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
import app.epistola.suite.catalog.versions.CatalogVersionTargeting
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.config.bindJsonb
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Releases a draft of an AUTHORED catalog as [version] (contract `releaseCatalogVersion`, ADR 0002):
 * records an explicit, immutable release boundary (SemVer + content fingerprint + notes + a manifest
 * snapshot) and advances the catalog's released-version pointer when it is the highest release.
 *
 * - The draft numbered [version] is released. When no draft has that number, the draft the caller
 *   means is released under it, renumbered as it is released; that number must be above every
 *   release. That is the draft [catalogVersion] names, or the current one.
 * - A draft that is behind releases (above its base, below its number) is released only when
 *   [acknowledgedBehind] names every one of them.
 * - Releasing changes no other draft. When no draft is open above the released version, the draft
 *   carries on as the next patch, as a copy of the release; otherwise it is discarded, its content
 *   being in the release.
 *
 * See [`docs/catalog-versioning.md`](../../../../../../../../../docs/catalog-versioning.md).
 */
data class ReleaseCatalogVersion(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
    val notes: String? = null,
    val publication: ReleasePublication = ReleasePublication.DEFAULT,
    /**
     * What the release contains. Only the 2.0 upgrade releases anything but the working copy
     * ([ContentSource.PUBLISHED_BEFORE_2_0]): content that was published already, so it is not held
     * to ready marks. Internal so no API can set it.
     */
    internal val source: ContentSource = ContentSource.WORKING_COPY,
    /** The releases the caller confirms this draft may be behind; naming others changes nothing. */
    val acknowledgedBehind: Set<String> = emptySet(),
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogVersionRef? = null,
) : CatalogVersionScoped,
    Command<ReleaseCatalogVersionResult>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_RELEASE
}

/**
 * What the caller wants to happen to publication for this one release.
 *
 * [DEFAULT] follows the catalog policy and tenant default; [PUBLISH] and [SKIP] are the
 * release-time override the UI offers where the policy permits one. [SUPPRESS] is internal and
 * stronger than [SKIP]: it is used when adopting an already-published release (a ZIP import), where
 * republishing would not be a user choice but an accident.
 */
enum class ReleasePublication { DEFAULT, PUBLISH, SKIP, SUPPRESS }

data class ReleaseCatalogVersionResult(
    val version: String,
    val fingerprint: String,
    val previousVersion: String?,
    val releasedAt: OffsetDateTime,
    /** True when the released content is byte-identical to a prior release. */
    val unchangedContent: Boolean,
    /** Set when the release was queued for publication; null when it was not. */
    val publicationId: UUID? = null,
    /** The open drafts with a higher number that are now behind this release. */
    val draftsBehind: List<String> = emptyList(),
    /** The draft the release carried on as, because none was open above it; null when it was discarded. */
    val openedDraft: String? = null,
)

/** Thrown when the requested release version is not a strictly increasing SemVer. */
class CatalogReleaseVersionException(message: String) : RuntimeException(message)

@Component
class ReleaseCatalogVersionHandler(
    private val jdbi: Jdbi,
    private val contentBuilder: CatalogContentBuilder,
    private val archiveBuilder: CatalogArchiveBuilder,
    private val fingerprintService: CatalogFingerprintService,
    private val revisionStore: ResourceRevisionStore,
    private val releaseEntryStore: ReleaseEntryStore,
    private val objectMapper: ObjectMapper,
    private val publicationPort: ObjectProvider<CatalogReleasePublicationPort>,
    private val stencilCheck: ReleaseStencilCheck,
    private val dependencyStore: CatalogDependencyStore,
    private val drafts: CatalogDrafts,
    private val targeting: CatalogVersionTargeting,
) : CommandHandler<ReleaseCatalogVersion, ReleaseCatalogVersionResult> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: ReleaseCatalogVersion): ReleaseCatalogVersionResult {
        val catalog = GetCatalog(command.tenantKey, command.catalogKey).query()
            ?: throw CatalogNotFoundException(command.catalogKey)
        if (catalog.type != CatalogType.AUTHORED) {
            throw CatalogReadOnlyException(command.catalogKey)
        }

        val newVersion = try {
            SemVer.parse(command.version)
        } catch (e: IllegalArgumentException) {
            throw CatalogReleaseVersionException(e.message ?: "Invalid version '${command.version}'")
        }

        val existing = loadReleases(command.tenantKey, command.catalogKey)
        val latest = existing.mapNotNull { SemVer.parseOrNull(it.version) }.maxOrNull()
        if (existing.any { it.version == newVersion.toString() }) {
            throw CatalogReleaseVersionException("Version $newVersion is released already")
        }
        val open = jdbi.withHandle<List<CatalogDraft>, Exception> { drafts.drafts(it, command.tenantKey, command.catalogKey) }
        val draft = open.firstOrNull { it.version == newVersion.toString() }
            ?: run {
                // Releasing the current draft under a new number, as the one-draft release dialog
                // does: like a renumber, but above every release, the order it was always released in.
                if (latest != null && newVersion <= latest) {
                    throw CatalogReleaseVersionException(
                        "Version $newVersion must be greater than the last release $latest",
                    )
                }
                jdbi.withHandle<CatalogDraft?, Exception> { drafts.draft(it, command.tenantKey, command.catalogKey) }
                    ?: throw CatalogVersionNotFoundException(command.catalogKey, command.version)
            }
        val behind = CatalogDrafts.behind(newVersion.toString(), draft.base, existing.map { it.version })
        val unacknowledged = behind.filter { it !in command.acknowledgedBehind }
        if (unacknowledged.isNotEmpty()) throw CatalogVersionBehindException(command.catalogKey, newVersion.toString(), unacknowledged)

        // Everything below reads and writes the draft being released, whichever one that is.
        return targeting.within(draft.versionId) { release(command, catalog, newVersion, latest, existing, draft, open) }
    }

    private fun release(
        command: ReleaseCatalogVersion,
        catalog: Catalog,
        newVersion: SemVer,
        latest: SemVer?,
        existing: List<ReleaseRow>,
        draft: CatalogDraft,
        open: List<CatalogDraft>,
    ): ReleaseCatalogVersionResult {
        if (command.source == ContentSource.WORKING_COPY) requireChangesReady(command.tenantKey, command.catalogKey)
        val pins = pinDependencies(command.tenantKey, command.catalogKey)
        val content = contentBuilder.build(command.tenantKey, command.catalogKey, command.source)
        fingerprintService.requirePublishable(content)
        stencilCheck.requireInstancesFit(command.tenantKey, command.catalogKey, content)
        val fingerprint = fingerprintService.fingerprint(content)
        // What this release contains, resource by resource. Recorded here because it cannot be
        // recovered afterwards: `manifest_snapshot` holds the manifest entries, not the payloads
        // they were built from. It is what lets the next release say which resources changed.
        val resourceFingerprints = fingerprintService.perResourceFingerprints(content)
        val unchanged = existing.any { fingerprintService.matchesFingerprint(content, it.fingerprint) }

        if (unchanged) {
            logger.warn(
                "Releasing catalog '{}' v{} with content identical to a previous release (fingerprint {})",
                command.catalogKey.value,
                newVersion,
                fingerprint,
            )
        }

        // released_at MUST come from the database clock — the same clock that
        // stamps resource updated_at / imported_at — so the AUTHORED
        // working-copy drift comparison (max(resource.updated_at) >
        // GREATEST(released_at, imported_at)) is exact. A JVM
        // OffsetDateTime.now() here drifts vs the DB clock and can make a
        // freshly released, unedited catalog look "pending".
        val releasedAt = jdbi.withHandle<OffsetDateTime, Exception> { handle ->
            handle.createQuery("SELECT NOW()").mapTo(OffsetDateTime::class.java).one()
        }
        val releaseInfo = ReleaseInfo(version = newVersion.toString(), releasedAt = releasedAt.toString(), fingerprint = fingerprint)
        val manifest = content.toManifest(releaseInfo)
        // Building the archive is expensive, so only do it when the release will actually be
        // queued. The bytes are handed to the outbox inside the release transaction below: a crash
        // between committing the release and recording publication intent must not be possible.
        val port = publicationPort.ifAvailable?.takeIf { shouldPublish(command, catalog, it) }
        val archive = port?.let { archiveBuilder.build(content, manifest) }
        var publicationId: UUID? = null
        val others = open.filter { it.versionId != draft.versionId }
        val above = others.filter { other -> SemVer.parseOrNull(other.version)?.let { it > newVersion } == true }
        val draftsBehind = above.filter { other -> other.base?.let(SemVer::parseOrNull)?.let { it < newVersion } ?: true }.map { it.version }
        val inUse = existing.map { it.version }.toSet() + others.map { it.version } + newVersion.toString()
        val openedDraft = if (above.isEmpty()) generateSequence(newVersion.bumpPatch()) { it.bumpPatch() }.map { it.toString() }.first { it !in inUse } else null
        val highest = latest == null || newVersion > latest

        jdbi.useTransaction<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO catalog_releases (tenant_key, catalog_id, version, fingerprint, notes, manifest_snapshot, released_at,
                                              content_retained, rendering_defaults_version, base_version)
                VALUES (:t, ${catalogAt("t", "c")}, :version, :fingerprint, :notes, CAST(:snapshot AS JSONB), :releasedAt, TRUE, :renderingDefaults, :base)
                """,
            )
                .bind("t", command.tenantKey)
                .bind("c", command.catalogKey)
                .bind("version", newVersion.toString())
                // The release keeps the draft's base (ADR 0002), which is gone once the draft is.
                .bind("base", draft.base)
                .bind("fingerprint", fingerprint)
                .bind("notes", command.notes)
                .bindJsonb("snapshot", manifest, objectMapper)
                .bind("releasedAt", releasedAt)
                .bind("renderingDefaults", RenderingDefaults.CURRENT.version)
                .execute()

            // The catalog's released version is its highest: a maintenance release of a lower line
            // (ADR 0002 "Ordering") does not move it back.
            if (highest) {
                handle.createUpdate(
                    """
                    UPDATE catalogs
                    SET released_version = :version, released_fingerprint = :fingerprint,
                        released_at = :releasedAt, updated_at = NOW()
                    WHERE tenant_key = :t AND catalog_key = :c
                    """,
                )
                    .bind("t", command.tenantKey)
                    .bind("c", command.catalogKey)
                    .bind("version", newVersion.toString())
                    .bind("fingerprint", fingerprint)
                    .bind("releasedAt", releasedAt)
                    .execute()
            }

            // Inside the release transaction: a release, the content it retains and the record of
            // what it contained commit together, so there is no state where one exists without the
            // others.
            dependencyStore.recordRelease(handle, command.tenantKey, command.catalogKey, newVersion.toString(), pins)
            val revisionDigests = revisionStore.retain(handle, command.tenantKey, content)
            releaseEntryStore.record(
                handle,
                command.tenantKey,
                command.catalogKey,
                newVersion.toString(),
                content,
                revisionDigests,
                resourceFingerprints,
            )

            // The draft becomes this release (contract ADR 0002). With no draft open above it, it
            // carries on as the next patch, its content the release's; otherwise it is discarded.
            if (openedDraft != null) {
                drafts.advance(handle, command.tenantKey, draft.versionId, openedDraft, base = newVersion.toString())
            } else {
                drafts.delete(handle, command.tenantKey, draft.versionId)
            }

            if (port != null && archive != null) {
                publicationId = port.recordReleasePublication(
                    handle,
                    CatalogReleasePublicationRequest(
                        tenantKey = command.tenantKey,
                        catalogKey = command.catalogKey,
                        version = newVersion.toString(),
                        fingerprint = fingerprint,
                        archive = archive,
                    ),
                )
            }
        }

        logger.info(
            "Released catalog '{}': {} -> {} (fingerprint {})",
            command.catalogKey.value,
            latest?.toString() ?: "—",
            newVersion,
            fingerprint,
        )

        return ReleaseCatalogVersionResult(
            version = newVersion.toString(),
            fingerprint = fingerprint,
            previousVersion = latest?.toString(),
            releasedAt = releasedAt,
            unchangedContent = unchanged,
            publicationId = publicationId,
            draftsBehind = draftsBehind,
            openedDraft = openedDraft,
        )
    }

    /**
     * Refuses the release while a resource that changed since the last release is not marked ready
     * as it is now (contract ADR 0002): a release contains only reviewed content.
     */
    private fun requireChangesReady(tenantKey: TenantKey, catalogKey: CatalogKey) {
        val notReady = GetCatalogResourceChanges(tenantKey, catalogKey).query().notReady
        if (notReady.isEmpty()) return
        throw ValidationException(
            field = "resources",
            message = "Cannot release ${catalogKey.value}: not ready: " + notReady.joinToString { "${it.type} '${it.slug}' (${it.state.name.lowercase()})" },
            code = ValidationCode.CATALOG_RESOURCES_NOT_READY,
        )
    }

    /**
     * The release of each declared dependency this release is cut with (contract ADR 0003): its lock.
     * A declaration still unlocked is locked first to the highest release that matches now. Refused
     * when a declaration matches no release, or its lock no longer holds because the release is gone
     * or no longer matches.
     */
    private fun pinDependencies(tenantKey: TenantKey, catalogKey: CatalogKey): List<CatalogDependencyDeclaration> = jdbi.inTransaction<List<CatalogDependencyDeclaration>, Exception> { handle ->
        val declarations = dependencyStore.declarations(handle, tenantKey, catalogKey)
        val locked = declarations.map { declaration ->
            if (declaration.locked) {
                declaration
            } else {
                dependencyStore.highestMatch(handle, tenantKey, declaration)
                    ?.let { declaration.copy(releaseVersion = it.version, releaseFingerprint = it.fingerprint) }
                    ?: declaration
            }
        }
        if (locked != declarations) dependencyStore.replace(handle, tenantKey, catalogKey, locked)
        val problems = locked.mapNotNull { declaration ->
            when {
                !declaration.locked -> "$declaration matches no release of it this tenant has that kept its content"

                !dependencyStore.lockHolds(handle, tenantKey, declaration) ->
                    "$declaration is locked to ${declaration.releaseVersion}, which this tenant no longer has as it was locked or which no longer matches"

                else -> null
            }
        }
        if (problems.isNotEmpty()) {
            throw ValidationException(
                field = "catalogDependencies",
                message = "Cannot release ${catalogKey.value}: dependency " + problems.joinToString("; "),
                code = ValidationCode.CATALOG_DEPENDENCY_UNRESOLVED,
            )
        }
        locked
    }

    /**
     * Resolves the catalog's publication policy against the tenant default and this release's
     * override. The integration only says whether publishing is possible at all; which releases go
     * out stays a catalog decision.
     */
    private fun shouldPublish(
        command: ReleaseCatalogVersion,
        catalog: Catalog,
        port: CatalogReleasePublicationPort,
    ): Boolean {
        val override = when (command.publication) {
            ReleasePublication.SUPPRESS -> return false
            ReleasePublication.DEFAULT -> null
            ReleasePublication.PUBLISH -> true
            ReleasePublication.SKIP -> false
        }
        if (!port.isPublicationAvailable(command.tenantKey, command.catalogKey)) return false
        val tenantDefault = requireNotNull(GetTenant(command.tenantKey).query()).publishCatalogsByDefault
        return catalog.exchangePublicationPolicy.resolve(tenantDefault, override)
    }

    private data class ReleaseRow(val version: String, val fingerprint: String)

    private fun loadReleases(tenantKey: TenantKey, catalogKey: CatalogKey): List<ReleaseRow> = jdbi.withHandle<List<ReleaseRow>, Exception> { handle ->
        handle.createQuery(
            "SELECT version, fingerprint FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}",
        )
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .map { rs, _ -> ReleaseRow(rs.getString("version"), rs.getString("fingerprint")) }
            .list()
    }
}

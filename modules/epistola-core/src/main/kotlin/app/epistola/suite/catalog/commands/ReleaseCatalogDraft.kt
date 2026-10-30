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
import app.epistola.suite.catalog.CatalogReleasePublicationPort
import app.epistola.suite.catalog.CatalogReleasePublicationRequest
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.dependencies.CatalogDependencyRelease
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.namespaces.CatalogNamespaceHolding
import app.epistola.suite.catalog.namespaces.NamespaceGrantStore
import app.epistola.suite.catalog.namespaces.holdingOf
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.GetCatalogResourceChanges
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.queries.ListReleaseDependencies
import app.epistola.suite.catalog.releases.AssessDraftNumber
import app.epistola.suite.catalog.releases.CatalogDependenciesUnreleasedException
import app.epistola.suite.catalog.releases.CatalogDraft
import app.epistola.suite.catalog.releases.CatalogDraftLifecycle
import app.epistola.suite.catalog.releases.CatalogDrafts
import app.epistola.suite.catalog.releases.CatalogReleaseBehindException
import app.epistola.suite.catalog.releases.CatalogReleaseNotFoundException
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.CatalogReleaseTargeting
import app.epistola.suite.catalog.releases.ChangeKind
import app.epistola.suite.catalog.releases.DraftNumberAssessment
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.releases.FrozenRelease
import app.epistola.suite.catalog.releases.GetCatalogDraft
import app.epistola.suite.catalog.releases.NextVersionHandOver
import app.epistola.suite.catalog.releases.ReleaseLineage
import app.epistola.suite.catalog.releases.ReleaseParent
import app.epistola.suite.catalog.releases.UnreleasedDependency
import app.epistola.suite.catalog.releases.validateDraftName
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.revisions.ReleaseDescription
import app.epistola.suite.catalog.revisions.ReleaseResourceStore
import app.epistola.suite.catalog.revisions.ResourceContentStore
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.config.bindJsonb
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
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
 * Releases a draft of a catalog (contract `releaseCatalogDraft`, ADR 0002): records an
 * explicit, immutable release boundary (SemVer + content fingerprint + notes + a manifest snapshot)
 * and freezes the draft's row as the release; the catalog's current release is its highest (ADR 0036).
 *
 * - Any draft can be released: [catalogVersion], or the next version when that is null.
 * - It is numbered when it is released: [version], or, when that is null, the number its changes
 *   need against its base, or the next free one of that kind ([AssessDraftNumber]). A given number
 *   must be free, above the base and no lower than the changes need.
 * - A draft that is behind releases for that number ([ReleaseLineage.behind]) is released only when
 *   [acknowledgedBehind] names every one of them.
 * - A declaration naming a draft of another catalog is refused
 *   ([CatalogDependenciesUnreleasedException]) unless [releaseDependencies] releases those drafts
 *   first, each numbered by its own changes, in the same transaction; the release then pins the
 *   releases they became.
 * - The draft becomes the release, which keeps its id; environments serving it serve the release.
 *   The next version, a convention of this suite, then continues on the release under a new id;
 *   parallel work is gone, unless [continueEverydayWork] makes it the next version, which then
 *   continues on its release the same way. Declarations in other drafts that named it now pin the
 *   release.
 *
 * See [`docs/catalog-versioning.md`](../../../../../../../../../docs/catalog-versioning.md).
 */
data class ReleaseCatalogDraft(
    val catalog: CatalogId,
    /** The number to release it as; null for the number its changes need. */
    val version: String? = null,
    val notes: String? = null,
    val publication: ReleasePublication = ReleasePublication.DEFAULT,
    /** The releases the caller confirms this draft may be behind; naming others changes nothing. */
    val acknowledgedBehind: Set<String> = emptySet(),
    /** The draft to release; null for the next version. */
    override val catalogVersion: CatalogReleaseRef? = null,
    /** Release the drafts of other catalogs this draft's declarations name, first. */
    val releaseDependencies: Boolean = false,
    /**
     * Releasing parallel work only: everyday work continues from this release, as the next version
     * does from its own ([app.epistola.suite.catalog.releases.NextVersionHandOver]). Null leaves the
     * next version as it is. A convention of this suite; the contract does not know it.
     */
    val continueEverydayWork: ContinueEverydayWork? = null,
    /**
     * Refuse a draft with nothing to release: on a base it changed nothing against, no resource and no
     * catalog-level content (`CatalogDraftUnchangedException`). The contract's `releaseCatalogDraft`
     * and MCP `release_draft` ask for it (ADR 0034: a release is new content, not another number for
     * its base's). False keeps a notes-only re-release possible for the callers that cut one on purpose.
     */
    val requireChange: Boolean = false,
) : CatalogReleaseScoped,
    Command<ReleaseCatalogDraftResult>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.CATALOG_RELEASE

    init {
        continueEverydayWork?.keepPreviousAs?.let(::validateDraftName)
    }
}

/**
 * Everyday work continues from the release of parallel work: the draft that was the next version is
 * kept as parallel work called [keepPreviousAs], or discarded with its changes when that is null.
 * Its changes are not merged into the release.
 */
data class ContinueEverydayWork(val keepPreviousAs: String? = null)

/**
 * What the caller wants to happen to publication for this one release.
 *
 * [DEFAULT] follows the catalog policy and tenant default; [PUBLISH] and [SKIP] are the
 * release-time override the UI offers where the policy permits one. [SUPPRESS] is internal and
 * stronger than [SKIP]: it is used when adopting an already-published release (a ZIP import), where
 * republishing would not be a user choice but an accident.
 */
enum class ReleasePublication { DEFAULT, PUBLISH, SKIP, SUPPRESS }

data class ReleaseCatalogDraftResult(
    val version: String,
    val fingerprint: String,
    val previousVersion: String?,
    val releasedAt: OffsetDateTime,
    /** True when the released content is byte-identical to a prior release. */
    val unchangedContent: Boolean,
    /** Set when the release was queued for publication; null when it was not. */
    val publicationId: UUID? = null,
    /** The catalog's other drafts this release puts behind, by name. */
    val draftsBehind: List<String> = emptyList(),
    /** The drafts of other catalogs released along with this one, dependencies first. */
    val releasedDependencies: List<ReleasedDependency> = emptyList(),
    /** The release's id (contract `CatalogVersionId`): the id the draft had. */
    val versionId: String = "",
    /** Declarations that pinned no release and were pinned to their catalog's highest release by this release. */
    val pinnedDependencies: List<PinnedDependency> = emptyList(),
)

/** A declaration this release pinned: the catalog it names and the release it now pins. */
data class PinnedDependency(val registry: String, val namespace: String, val catalogKey: String, val releaseVersion: String)

/** A draft of another catalog released along with a release, and the release it became. */
data class ReleasedDependency(val registry: String, val namespace: String, val catalogKey: String, val versionId: String, val releaseVersion: String)

/** Thrown when the requested release version is not a strictly increasing SemVer. */
class CatalogReleaseVersionException(message: String) : RuntimeException(message)

/** The drafts a release is releasing its dependencies for, as `catalog/draft`: to refuse a cycle. */
private val RELEASING: ScopedValue<Set<String>> = ScopedValue.newInstance()

@Component
class ReleaseCatalogDraftHandler(
    private val jdbi: Jdbi,
    private val contentBuilder: CatalogContentBuilder,
    private val archiveBuilder: CatalogArchiveBuilder,
    private val fingerprintService: CatalogFingerprintService,
    private val revisionStore: ResourceContentStore,
    private val releaseResourceStore: ReleaseResourceStore,
    private val objectMapper: ObjectMapper,
    private val publicationPort: ObjectProvider<CatalogReleasePublicationPort>,
    private val releaseChecks: List<app.epistola.suite.catalog.releases.ReleaseCheck>,
    private val dependencyStore: CatalogDependencyStore,
    private val drafts: CatalogDrafts,
    private val targeting: CatalogReleaseTargeting,
    private val handOver: NextVersionHandOver,
    private val installation: app.epistola.suite.installation.InstallationService,
    private val identity: app.epistola.suite.catalog.revisions.ReleaseIdentity,
    private val grants: NamespaceGrantStore,
    private val versionResources: app.epistola.suite.catalog.revisions.VersionResourceReader,
    private val draftLifecycle: List<CatalogDraftLifecycle>,
) : CommandHandler<ReleaseCatalogDraft, ReleaseCatalogDraftResult> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: ReleaseCatalogDraft): ReleaseCatalogDraftResult {
        val catalog = GetCatalog(command.catalog).query()
            ?: throw CatalogNotFoundException(command.catalogKey)
        // Releasing is changing the catalog. A release received from elsewhere is recorded by
        // RecordInstalledRelease instead.
        requireCatalogEditable(command.catalog)
        // The holder of a namespace issues its numbers (ADR 0034, section 2): releasing needs the
        // tenant to hold the catalog's, or the catalog to be local.
        val holding = jdbi.withHandle<CatalogNamespaceHolding, Exception> { holdingOf(it, grants, command.catalog) }
        holding.refusal?.let { throw ValidationException("namespace", it, ValidationCode.CATALOG_NAMESPACE_NOT_HELD) }

        val draft = jdbi.withHandle<CatalogDraft?, Exception> { handle ->
            drafts.find(handle, command.catalog, command.catalogVersion?.draft ?: DraftRef.Next)
        } ?: throw CatalogReleaseNotFoundException(command.catalogKey, command.catalogVersion?.draft?.toString() ?: DraftRef.NEXT)
        val keepAs = command.continueEverydayWork?.keepPreviousAs?.trim()
        val handsOver = command.continueEverydayWork != null && !draft.isNext
        if (handsOver) jdbi.useHandle<Exception> { handOver.check(it, command.catalog, draft, keepAs) }

        // Drafts of other catalogs this one builds on are released first, or the release is refused.
        val releasedDependencies = targeting.within(draft.versionId) { releaseDependencies(command, draft) }

        // A release is a change when asked (contract `releaseCatalogDraft`, ADR 0034): a draft on a base
        // that changed nothing against it, no resource and no catalog-level content, has nothing to
        // release. A draft without a base always has its first release.
        val base = draft.base
        if (command.requireChange && base != null &&
            !GetCatalogResourceChanges(command.catalog, catalogVersion = app.epistola.suite.catalog.releases.CatalogReleaseRef(command.catalog, draft.ref)).query().hasUnreleasedChanges
        ) {
            throw app.epistola.suite.catalog.releases.CatalogDraftUnchangedException(command.catalogKey, draft.label, base)
        }

        val existing = loadReleases(command.catalog)
        val latest = existing.mapNotNull { SemVer.parseOrNull(it.version) }.maxOrNull()
        val newVersion = number(command, draft, existing)
        val others = jdbi.withHandle<List<CatalogDraft>, Exception> { drafts.drafts(it, command.catalog) }.filter { it.versionId != draft.versionId }
        val lineage = jdbi.withHandle<ReleaseLineage, Exception> { ReleaseLineage.read(it, command.catalog) }
        val behind = lineage.behind(draft.base, draft.merged, newVersion.toString())
        val unacknowledged = behind.filter { it !in command.acknowledgedBehind }
        if (unacknowledged.isNotEmpty()) throw CatalogReleaseBehindException(command.catalogKey, draft.label, unacknowledged)
        // The other drafts this release puts behind: those numbered above it once it has its number.
        val draftsBehind = others.filter { other ->
            val assessment = AssessDraftNumber(command.catalog, other.ref).query() ?: return@filter false
            val theirs = DraftNumberAssessment.nextFree(SemVer.parse(assessment.needed), assessment.needs ?: ChangeKind.NONE, assessment.taken + newVersion.toString())
            other.base != newVersion.toString() && newVersion < theirs
        }.map { it.label }

        // Everything below reads and writes the draft being released, whichever one that is.
        return targeting.within(draft.versionId) { release(command, catalog, newVersion, latest, existing, draft, draftsBehind) }
            .copy(releasedDependencies = releasedDependencies)
    }

    /**
     * Releases, recursively and first, the drafts of other catalogs [draft]'s declarations name, when
     * [ReleaseCatalogDraft.releaseDependencies] asks; refuses the release when it does not. A named
     * draft with nothing of its own to release is not released: the declaration pins the release it
     * is based on, with or without the flag. Each other one is released by its own rules (behind,
     * readiness), so one refusal refuses all, and two catalogs naming each other's drafts are refused.
     * Runs targeting [draft], whose declarations it reads.
     */
    private fun releaseDependencies(command: ReleaseCatalogDraft, draft: CatalogDraft): List<ReleasedDependency> {
        // A declaration made by name, of a catalog with nothing released yet, names its next version.
        val named = jdbi.withHandle<List<CatalogDependencyDeclaration>, Exception> { handle ->
            dependencyStore.declarations(handle, command.catalog).map { dependencyStore.asPublished(handle, command.tenantKey, it) }
        }.filter { it.namesDraft }
        val changed = mutableListOf<CatalogDependencyDeclaration>()
        for (declaration in named) {
            val base = unchangedBase(declaration.catalogIn(command.tenantKey), refOf(command.tenantKey, declaration))
            if (base != null) pinDeclaration(command, declaration, base) else changed += declaration
        }
        if (changed.isEmpty()) return emptyList()
        if (!command.releaseDependencies) {
            throw CatalogDependenciesUnreleasedException(command.catalogKey, unreleased(command.tenantKey, changed, mutableSetOf()))
        }
        val releasing = (if (RELEASING.isBound) RELEASING.get() else emptySet()) + "${command.catalogKey.value}/${draft.key}"
        val released = mutableListOf<ReleasedDependency>()
        for (declaration in changed) {
            if ("${declaration.catalogKey}/${declaration.draftId}" in releasing) {
                throw ValidationException(
                    "releaseDependencies",
                    "${command.catalogKey.value} and ${declaration.catalogKey} build on each other's drafts; release one of them first and pin it",
                    ValidationCode.CATALOG_DEPENDENCY_INVALID,
                )
            }
            val result = ScopedValue.where(RELEASING, releasing).call<ReleaseCatalogDraftResult, RuntimeException> {
                ReleaseCatalogDraft(
                    catalog = declaration.catalogIn(command.tenantKey),
                    catalogVersion = refOf(command.tenantKey, declaration),
                    releaseDependencies = true,
                ).execute()
            }
            released += result.releasedDependencies
            released += ReleasedDependency(declaration.registry, declaration.namespace, declaration.catalogKey, result.versionId, result.version)
        }
        return released.distinctBy { it.registry to it.namespace to it.catalogKey }
    }

    private fun refOf(tenantKey: TenantKey, declaration: CatalogDependencyDeclaration) = CatalogReleaseRef(declaration.catalogIn(tenantKey), DraftRef.Id(requireNotNull(declaration.draftId)))

    /**
     * The release [ref] is based on, when that draft holds nothing a release of it would add: no
     * resource or catalog setting (its default theme, presets, license, dependencies) changed against
     * its base, no draft named, and the same pins as its base. Null when it has something to release.
     */
    private fun unchangedBase(catalog: CatalogId, ref: CatalogReleaseRef): String? {
        val base = GetCatalogDraft(catalog, ref.draft).query()?.base ?: return null
        if (GetCatalogResourceChanges(catalog, ref).query().hasUnreleasedChanges) return null
        val declared = ListCatalogDependencies(catalog, ref).query()
        if (declared.any { it.namesDraft }) return null
        val pins = { list: List<CatalogDependencyDeclaration> -> list.map { it.identity to it.releaseVersion }.toSet() }
        return base.takeIf { pins(declared) == pins(ListReleaseDependencies(catalog, base).query()) }
    }

    /** [declaration] of the draft being released now pins [version] of the catalog it names. */
    private fun pinDeclaration(command: ReleaseCatalogDraft, declaration: CatalogDependencyDeclaration, version: String) = jdbi.useHandle<Exception> { handle ->
        val pin = dependencyStore.release(handle, command.tenantKey, declaration, version)
            ?: throw CatalogReleaseVersionException("Release $version of ${declaration.catalogKey} cannot be pinned")
        val declarations = dependencyStore.declarations(handle, command.catalog).map {
            if (it.identity != declaration.identity) it else it.copy(releaseVersion = pin.version, releaseFingerprint = pin.fingerprint, draftId = null, draftName = null)
        }
        dependencyStore.replace(handle, command.catalog, declarations)
    }

    /** [named], each followed by the drafts it names in turn, once each. */
    private fun unreleased(tenantKey: TenantKey, named: List<CatalogDependencyDeclaration>, seen: MutableSet<String>): List<UnreleasedDependency> = named.flatMap { declaration ->
        val key = requireNotNull(declaration.draftId)
        if (!seen.add("${declaration.catalogKey}/$key")) return@flatMap emptyList()
        val own = UnreleasedDependency(declaration.registry, declaration.namespace, declaration.catalogKey, key, declaration.draftName.orEmpty())
        val other = declaration.catalogIn(tenantKey)
        val further = ListCatalogDependencies(other, CatalogReleaseRef(other, DraftRef.Id(key))).query().filter { it.namesDraft }
        listOf(own) + unreleased(tenantKey, further, seen)
    }

    /**
     * The number [draft] is released as: the one asked for, or the one its changes need.
     */
    private fun number(command: ReleaseCatalogDraft, draft: CatalogDraft, existing: List<ReleaseRow>): SemVer {
        val asked = command.version
        if (asked != null && existing.any { it.version == asked.trim() }) throw CatalogReleaseVersionException("Version ${asked.trim()} is released already")
        val assessment = AssessDraftNumber(command.catalog, draft.ref).query()
            ?: throw CatalogReleaseNotFoundException(command.catalogKey, draft.label)
        val chosen = asked?.trim() ?: assessment.suggested
        assessment.refuses(chosen)?.let { throw CatalogReleaseVersionException(it) }
        return SemVer.parse(chosen)
    }

    private fun release(
        command: ReleaseCatalogDraft,
        catalog: Catalog,
        newVersion: SemVer,
        latest: SemVer?,
        existing: List<ReleaseRow>,
        draft: CatalogDraft,
        draftsBehind: List<String>,
    ): ReleaseCatalogDraftResult {
        requireChangesReady(command.catalog)
        val pinnedNow = pinUnpinned(command.catalog)
        val pins = pinDependencies(command.catalog)
        val content = contentBuilder.build(command.catalog)
        fingerprintService.requirePublishable(content)
        releaseChecks.forEach { it.requireSatisfied(command.catalog, content) }
        val fingerprint = fingerprintService.fingerprint(content)
        // Its identity (ADR 0034): the fingerprint, which is V6, a content hash.
        val contentHash = fingerprint.takeIf(app.epistola.suite.catalog.CatalogCanonicalizer::isContentHash) ?: fingerprintService.contentHash(content)
        // What this release contains, resource by resource. Recorded here because it cannot be
        // recovered afterwards: `manifest_snapshot` holds the manifest entries, not the payloads
        // they were built from. It is what lets the next release say which resources changed.
        val resourceFingerprints = fingerprintService.perResourceFingerprints(content)
        val unchanged = existing.any { it.contentHash == contentHash || fingerprintService.matchesFingerprint(content, it.fingerprint) }

        if (unchanged) {
            logger.warn(
                "Releasing catalog '{}' v{} with content identical to a previous release (fingerprint {})",
                command.catalogKey.value,
                newVersion,
                fingerprint,
            )
        }

        // released_at MUST come from the database clock — the same clock that
        // stamps resource updated_at / imported_at — so the
        // working-copy drift comparison (max(resource.updated_at) >
        // GREATEST(released_at, imported_at)) is exact. A JVM
        // OffsetDateTime.now() here drifts vs the DB clock and can make a
        // freshly released, unedited catalog look "pending".
        val releasedAt = jdbi.withHandle<OffsetDateTime, Exception> { handle ->
            handle.createQuery("SELECT NOW()").mapTo(OffsetDateTime::class.java).one()
        }
        // The release says what it is (wire v8): the draft's id, the rendering defaults it renders
        // with and the installation that cut it, and the release it was made from, the draft's base.
        val releasedByInstallation = installation.get().id

        // The releases merged into the draft (ADR 0032) are its merge parents: the release contains
        // them, and says so wherever it goes, so it is not behind them after it is released either.
        // Lineage is by hash (ADR 0034): a parent is named by its content hash, its number kept for people.
        fun hashOf(version: String?): String? = version?.let { v ->
            existing.firstOrNull { it.version == v }?.let { row -> row.contentHash ?: row.fingerprint }
        }
        val mergeParents = draft.merged.sortedBy { SemVer.parseOrNull(it) }.map { merged -> ReleaseParent(merged, hashOf(merged)) }
        val releaseInfo = ReleaseInfo(
            version = newVersion.toString(),
            releasedAt = releasedAt.toString(),
            fingerprint = fingerprint,
            releaseId = ReleaseDescription.releaseId(draft.key),
            metadata = ReleaseDescription.metadata(RenderingDefaults.CURRENT.version, releasedByInstallation = releasedByInstallation, merged = mergeParents),
            base = ReleaseDescription.base(draft.base, hashOf(draft.base)),
        )
        val manifest = content.toManifest(releaseInfo)
        // Building the archive is expensive, so only do it when the release will actually be
        // queued. The bytes are handed to the outbox inside the release transaction below: a crash
        // between committing the release and recording publication intent must not be possible.
        val port = publicationPort.ifAvailable?.takeIf { shouldPublish(command, catalog, it) }
        val archive = port?.let { archiveBuilder.build(content, manifest) }
        var publicationId: UUID? = null

        jdbi.useTransaction<Exception> { handle ->
            // The catalog's current release is a query over its released rows (ADR 0036,
            // `catalog_current_releases`): its highest, so a maintenance release of a lower line (ADR
            // 0002 "Ordering") does not move it back. The catalog itself only records that it changed.
            handle.createUpdate("UPDATE catalogs SET updated_at = NOW() WHERE tenant_key = :t AND catalog_id = epistola_catalog_id(:t, :c_ns, :c)")
                .bind("t", command.tenantKey)
                .bindCatalog("c", command.catalog)
                .execute()

            // Inside the release transaction: a release, the content it retains and the record of
            // what it contained commit together, so there is no state where one exists without the
            // others.
            // What a draft keeps beside a released form (a code list's source) goes with it (ADR 0033):
            // the release stores the content its draft does.
            val settings = versionResources.settings(handle, command.catalog)
            val retained = revisionStore.retain(handle, command.tenantKey, content, settings)
            // Parallel work released for everyday work to continue from becomes the next version first.
            val handsOver = command.continueEverydayWork != null && !draft.isNext
            if (handsOver) handOver.apply(handle, command.catalog, draft, command.continueEverydayWork.keepPreviousAs?.trim())
            // The draft becomes the release (ADR 0036): its row is frozen, keeping its id and name, and
            // its content rows become the release's below. Environments serving the draft name its row,
            // so they serve the release once it is frozen; they are read first, to log each.
            val onReleased = draftLifecycle.map { it.draftReleasing(handle, command.tenantKey, draft.versionId) }
            drafts.freeze(
                handle,
                command.catalog,
                draft.versionId,
                FrozenRelease(
                    version = newVersion.toString(),
                    fingerprint = fingerprint,
                    contentHash = contentHash,
                    notes = command.notes,
                    manifestSnapshot = objectMapper.writeValueAsString(manifest),
                    renderingDefaultsVersion = RenderingDefaults.CURRENT.version,
                    releasedAt = releasedAt,
                    // Who released it: the user, and this installation.
                    releasedBy = currentUserIdOrNull()?.value,
                    releasedByInstallation = releasedByInstallation,
                    // The release keeps the draft's base (ADR 0002), which is gone once the draft is.
                    base = draft.base,
                    baseFingerprint = releaseInfo.base?.fingerprint,
                ),
            )
            ReleaseLineage.recordMerged(handle, command.catalog, newVersion.toString(), mergeParents)
            if (draft.isNext || handsOver) {
                // The next version, a convention of this suite, continues on this release: a new row
                // holding what the release holds, measured against it.
                drafts.startNext(handle, command.catalog, draft.versionId, base = newVersion.toString())
            }
            drafts.retire(handle, command.tenantKey, draft.versionId)
            // Its declarations are its pins (ADR 0036): recorded on its row once it is the release's.
            dependencyStore.recordRelease(handle, command.catalog, newVersion.toString(), pins)
            releaseResourceStore.record(
                handle,
                command.catalog,
                newVersion.toString(),
                content,
                retained.contents,
                resourceFingerprints,
                variants = retained.variants,
            )

            // Declarations in other drafts that named this draft now pin the release it became.
            dependencyStore.pinDeclarationsOf(handle, command.tenantKey, draft.versionId, CatalogDependencyRelease(newVersion.toString(), fingerprint))
            // The draft became the release, keeping its id: environments serving it serve the release
            // (contract ADR 0003), logged as released.
            onReleased.forEach { it(newVersion.toString()) }

            if (port != null && archive != null) {
                publicationId = port.recordReleasePublication(
                    handle,
                    CatalogReleasePublicationRequest(
                        catalog = command.catalog,
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

        return ReleaseCatalogDraftResult(
            version = newVersion.toString(),
            fingerprint = fingerprint,
            previousVersion = latest?.toString(),
            releasedAt = releasedAt,
            unchangedContent = unchanged,
            publicationId = publicationId,
            draftsBehind = draftsBehind,
            versionId = draft.key,
            pinnedDependencies = pinnedNow,
        )
    }

    /**
     * Pins every declaration of [catalog] that pins no release and names no draft to the highest
     * release of the catalog it names, when that catalog has one: a declaration made by name (the
     * 2.0 upgrade declares what a 1.x catalog used before any of them had a release) is pinned when
     * the catalog is released, as the release dialog says. One whose catalog has no release stays
     * unpinned, which [pinDependencies] refuses, naming it.
     */
    private fun pinUnpinned(catalog: CatalogId): List<PinnedDependency> = jdbi.inTransaction<List<PinnedDependency>, Exception> { handle ->
        val declarations = dependencyStore.declarations(handle, catalog)
        val pinned = mutableListOf<PinnedDependency>()
        val updated = declarations.map { declaration ->
            if (declaration.pinned || declaration.namesDraft) return@map declaration
            val highest = dependencyStore.highest(handle, catalog.tenantKey, declaration) ?: return@map declaration
            pinned += PinnedDependency(declaration.registry, declaration.namespace, declaration.catalogKey, highest.version)
            declaration.copy(releaseVersion = highest.version, releaseFingerprint = highest.fingerprint)
        }
        if (pinned.isNotEmpty()) dependencyStore.replace(handle, catalog, updated)
        pinned
    }

    /**
     * Refuses the release while a resource that changed since the last release is not marked ready
     * as it is now (contract ADR 0002): a release contains only reviewed content.
     */
    private fun requireChangesReady(catalog: CatalogId) {
        val notReady = GetCatalogResourceChanges(catalog).query().notReady
        if (notReady.isEmpty()) return
        throw ValidationException(
            field = "resources",
            message = "Cannot release ${catalog.key.value}: not ready: " + notReady.joinToString { "${it.type} '${it.slug}' (${it.state.name.lowercase()})" },
            code = ValidationCode.CATALOG_RESOURCES_NOT_READY,
        )
    }

    /**
     * The release of each declared dependency this release is cut with (contract ADR 0003): its pin.
     * Refused when a declaration pins no release, or the tenant no longer has the pinned release with
     * the fingerprint the pin recorded.
     */
    private fun pinDependencies(catalog: CatalogId): List<CatalogDependencyDeclaration> = jdbi.withHandle<List<CatalogDependencyDeclaration>, Exception> { handle ->
        val declarations = dependencyStore.declarations(handle, catalog)
        val problems = declarations.mapNotNull { declaration ->
            when {
                !declaration.pinned -> "${declaration.catalogKey} has no release to pin; release ${declaration.catalogKey} first"

                !dependencyStore.pinHolds(handle, catalog.tenantKey, declaration) ->
                    "$declaration pins ${declaration.releaseVersion}, which this tenant no longer has as it was pinned"

                else -> null
            }
        }
        if (problems.isNotEmpty()) {
            throw ValidationException(
                field = "catalogDependencies",
                message = "Cannot release ${catalog.key.value}: dependency " + problems.joinToString("; "),
                code = ValidationCode.CATALOG_DEPENDENCY_UNRESOLVED,
            )
        }
        declarations
    }

    /**
     * Resolves the catalog's publication policy against the tenant default and this release's
     * override. The integration only says whether publishing is possible at all; which releases go
     * out stays a catalog decision.
     */
    private fun shouldPublish(
        command: ReleaseCatalogDraft,
        catalog: Catalog,
        port: CatalogReleasePublicationPort,
    ): Boolean {
        val override = when (command.publication) {
            ReleasePublication.SUPPRESS -> return false
            ReleasePublication.DEFAULT -> null
            ReleasePublication.PUBLISH -> true
            ReleasePublication.SKIP -> false
        }
        if (!port.isPublicationAvailable(command.catalog)) return false
        val tenantDefault = requireNotNull(GetTenant(command.tenantKey).query()).publishCatalogsByDefault
        return catalog.exchangePublicationPolicy.resolve(tenantDefault, override)
    }

    private data class ReleaseRow(val version: String, val fingerprint: String, val releasedAt: OffsetDateTime?, val contentHash: String? = null)

    private fun loadReleases(catalog: CatalogId): List<ReleaseRow> = jdbi.withHandle<List<ReleaseRow>, Exception> { handle ->
        handle.createQuery(
            "SELECT version, fingerprint, released_at, content_hash FROM catalog_versions WHERE state = 'released' AND tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}",
        )
            .bind("t", catalog.tenantKey)
            .bindCatalog("c", catalog)
            .map { rs, _ -> ReleaseRow(rs.getString("version"), rs.getString("fingerprint"), rs.getObject("released_at", OffsetDateTime::class.java), rs.getString("content_hash")) }
            .list()
    }
}

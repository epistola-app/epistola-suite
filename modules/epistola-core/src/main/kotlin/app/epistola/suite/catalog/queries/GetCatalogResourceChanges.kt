// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.ready.ReadyMarks
import app.epistola.suite.catalog.releases.CatalogDrafts
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.releases.GetCatalogDraft
import app.epistola.suite.catalog.revisions.ReleaseResource
import app.epistola.suite.catalog.revisions.ReleaseResourceStore
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * What the next release of a catalog would contain that the last one did not — resource by
 * resource, and what it holds beside its resources ([CatalogSettingChange]: its dependencies,
 * default theme, presets and license).
 *
 * A draft has changes when its **content** differs from the release it is based on (ADR 0034): the
 * content hash covers its resources and those catalog-level settings, and nothing that names or
 * presents the catalog (name, description, keywords, presentation, attributes), so neither does
 * this. [GetCatalogReleaseStatus] answers *whether* the working copy has drifted from the release;
 * this answers *where*.
 *
 * O(catalog-size), like the drift check it refines — it builds the working copy once. Read it for a
 * page about one catalog, not for a list of them.
 */
data class GetCatalogResourceChanges(
    val catalog: CatalogId,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Query<CatalogResourceChanges>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.CATALOG_VIEW
}

/** Where one resource of the working copy stands relative to the catalog's last release. */
enum class CatalogResourceState {
    /** In the working copy and not in the last release. */
    NEW,

    /** In both, with different content. */
    MODIFIED,

    /** In both, byte-identical. */
    RELEASED,

    /** In the last release and no longer in the working copy. */
    REMOVED,
}

data class CatalogResourceChange(
    val type: String,
    val slug: String,
    /** As the working copy names it, or as the last release did for a resource dropped since. */
    val name: String?,
    val state: CatalogResourceState,
    /**
     * Whether its content is marked ready as it is now (contract ADR 0002). Only a changed resource
     * needs it; a removed one has no content to mark.
     */
    val ready: Boolean = false,
    /**
     * Whether a merge into this draft wrote it as merged content that nobody has reviewed since
     * ([ReadyMarks.mergedAwaitingReview]): neither side's, so it is reviewed before it is published.
     * Only a changed resource is.
     */
    val mergedForReview: Boolean = false,
) {
    /** Whether releasing waits on this resource: it changed and is not ready. */
    val blocksRelease: Boolean get() = state in CHANGED && !ready

    private companion object {
        val CHANGED = setOf(CatalogResourceState.NEW, CatalogResourceState.MODIFIED)
    }
}

data class CatalogResourceChanges(
    val catalogKey: CatalogKey,
    val latestVersion: String?,
    /** Ordered by type then slug, the order the manifest lists them in. */
    val resources: List<CatalogResourceChange>,
    /**
     * What changed beside the resources, by entry; only changes are listed. None of them needs a
     * ready mark, so none is in [notReady].
     */
    val settings: List<CatalogSettingChange> = emptyList(),
) {
    val added: List<CatalogResourceChange> get() = inState(CatalogResourceState.NEW)
    val modified: List<CatalogResourceChange> get() = inState(CatalogResourceState.MODIFIED)
    val removed: List<CatalogResourceChange> get() = inState(CatalogResourceState.REMOVED)
    val released: List<CatalogResourceChange> get() = inState(CatalogResourceState.RELEASED)

    /** A resource or a catalog setting differs from the base: there is something to release. */
    val hasUnreleasedChanges: Boolean get() = resources.any { it.state != CatalogResourceState.RELEASED } || settings.isNotEmpty()

    /** The changed resources not marked ready, which a release refuses. */
    val notReady: List<CatalogResourceChange> get() = resources.filter { it.blocksRelease }

    /** The changed resources a merge wrote as merged content that await review. */
    val mergedForReview: List<CatalogResourceChange> get() = resources.filter { it.mergedForReview }

    private fun inState(state: CatalogResourceState) = resources.filter { it.state == state }
}

@Component
class GetCatalogResourceChangesHandler(
    private val jdbi: Jdbi,
    private val contentBuilder: CatalogContentBuilder,
    private val fingerprintService: CatalogFingerprintService,
    private val releaseResourceStore: ReleaseResourceStore,
    private val readyMarks: ReadyMarks,
    private val drafts: CatalogDrafts,
    private val assembler: app.epistola.suite.catalog.revisions.ReleaseContentAssembler,
    private val dependencyStore: CatalogDependencyStore,
) : QueryHandler<GetCatalogResourceChanges, CatalogResourceChanges> {

    override fun handle(query: GetCatalogResourceChanges): CatalogResourceChanges {
        GetCatalog(query.catalog).query() ?: throw CatalogNotFoundException(query.catalogKey)
        // Only a catalog that can be changed here has a next release to describe (ADR 0032): one the
        // suite supplies, or any in a run-only installation, only receives releases.
        val editability = GetCatalogEditability(query.catalog).query()
        if (!editability.editable) throw CatalogReadOnlyException(query.catalogKey, editability.reason)

        val content = contentBuilder.build(query.catalog)
        val working = fingerprintService.resourceFingerprints(content)
        val release = loadBaseRelease(query.catalog)
        val released = release?.let { entriesOf(query.catalog, it.version) }.orEmpty()

        // The working copy names a resource it still has; the release names one dropped since.
        val names = content.resourceEntries.associate { "${it.type}/${it.key}" to it.name } +
            released.filterKeys { it !in working }.mapValues { (_, entry) -> entry.name }

        val readiness = jdbi.withHandle<Map<String, Boolean>, Exception> { readyMarks.readiness(it, query.catalog) }
        val mergedForReview = jdbi.withHandle<Set<String>, Exception> { readyMarks.mergedAwaitingReview(it, query.catalog) }

        fun change(key: String, state: CatalogResourceState) = CatalogResourceChange(
            type = key.substringBefore('/'),
            slug = key.substringAfter('/'),
            name = names[key],
            state = state,
            ready = readiness[key] == true,
            mergedForReview = state != CatalogResourceState.RELEASED && state != CatalogResourceState.REMOVED && key in mergedForReview,
        )

        // The draft's own declarations, which may name a draft of another catalog; the wire form cannot.
        val declarations = jdbi.withHandle<List<CatalogDependencyDeclaration>, Exception> { dependencyStore.declarations(it, query.catalog) }
        // A declaration naming a draft pins that draft's base when it has nothing to publish.
        val namedDraftBases = declarations.mapNotNull { declaration ->
            val draftId = declaration.draftId ?: return@mapNotNull null
            GetCatalogDraft(declaration.catalogIn(query.tenantKey), DraftRef.Id(draftId)).query()?.base?.let { declaration.alias to it }
        }.toMap()
        val draftSettings = CatalogSettings(content.catalog, declarations, namedDraftBases)
        val base = baseSettings(query.catalog, release?.version)
        val settings = renderingDefaultsChange(release?.renderingDefaults) + CatalogSettingDiff.between(query.catalogKey, base = base, draft = draftSettings) { alias ->
            // The catalog an alias names, by the draft's declaration of it, or the release's for one
            // the draft no longer declares (ADR 0034, section 9).
            ((declarations + base.dependencies).firstOrNull { it.alias == alias }?.catalogIn(query.tenantKey) ?: query.catalog.takeIf { alias == query.catalogKey.value })
                ?.let { GetCatalog(it).query()?.name }
        }

        // Nothing released yet: every resource is new, and that is a complete answer.
        if (release == null) {
            return CatalogResourceChanges(
                catalogKey = query.catalogKey,
                latestVersion = null,
                resources = working.keys.sorted().map { change(it, CatalogResourceState.NEW) },
                settings = settings,
            )
        }

        val baseline = baseline(query.catalog, release.version)

        val resources = (working.keys + baseline.keys).sorted().map { key ->
            val state = when {
                key !in baseline -> CatalogResourceState.NEW
                key !in working -> CatalogResourceState.REMOVED
                working[key] != baseline[key] -> CatalogResourceState.MODIFIED
                else -> CatalogResourceState.RELEASED
            }
            change(key, state)
        }

        return CatalogResourceChanges(
            catalogKey = query.catalogKey,
            latestVersion = release.version,
            resources = resources,
            settings = settings,
        )
    }

    /**
     * The catalog-level content [version] was released with, as its manifest froze it (what its
     * content hash covers); [CatalogSettings.NONE] before a first release.
     */
    private fun baseSettings(catalog: CatalogId, version: String?): CatalogSettings {
        val manifest = version?.let { assembler.readManifest(catalog, it) } ?: return CatalogSettings.NONE
        return CatalogSettings(manifest.catalog, manifest.catalogDependencies.orEmpty().map(CatalogDependencyDeclaration::of))
    }

    /**
     * The draft renders with newer rendering defaults than its base release was cut with (#1127): a
     * change of what every document looks like, so the draft has something to release even when its
     * content is the release's. None when the base was cut with the current defaults, or has none.
     */
    private fun renderingDefaultsChange(base: Int?): List<CatalogSettingChange> {
        val current = app.epistola.generation.pdf.RenderingDefaults.CURRENT
        if (base == null || base == current.version) return emptyList()
        return listOf(
            CatalogSettingChange(
                entry = RENDERING_DEFAULTS_ENTRY,
                state = CatalogResourceState.MODIFIED,
                name = "Rendering defaults V$base → V${current.version}",
                description = "documents may look different. ${current.summary}".trim(),
            ),
        )
    }

    private data class ReleaseRow(val version: String, val fingerprint: String, val renderingDefaults: Int?)

    /**
     * Each resource of [version] by the function [working] is compared with: what the draft recorded
     * holding of it when it is the draft's base and was put in place as this suite holds content
     * (the draft's `base_fingerprints`), else its retained content, fingerprinted now. Never the
     * digests `release_resources` stored, which another function computed.
     */
    private fun baseline(catalog: CatalogId, version: String): Map<String, String> {
        val held = jdbi.withHandle<String?, Exception> { handle ->
            drafts.draft(handle, catalog)?.takeIf { it.base == version }?.let { drafts.baseFingerprints(handle, catalog.tenantKey, it.versionId) }
        }
        fingerprintService.readBaseline(held)?.let { recorded -> return recorded.filterKeys { !app.epistola.suite.catalog.merge.CatalogSetting.isSettingKey(it) } }
        return assembler.assemble(catalog, version)?.let { fingerprintService.resourceFingerprints(it.content) }.orEmpty()
    }

    /**
     * What the draft the caller means is compared against (contract ADR 0002): its base, the release it
     * started from, or, when it records none that the catalog still has, the highest release below its
     * number. Without a draft, the latest release.
     */
    private fun loadBaseRelease(catalog: CatalogId): ReleaseRow? = jdbi.withHandle<ReleaseRow?, Exception> { handle ->
        val releases = handle.createQuery(
            """
            SELECT version, fingerprint, rendering_defaults_version
            FROM catalog_releases
            WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}
            $LATEST_RELEASE_ORDER
            """,
        )
            .bind("t", catalog.tenantKey)
            .bindCatalog("c", catalog)
            .map { rs, _ -> ReleaseRow(version = rs.getString("version"), fingerprint = rs.getString("fingerprint"), renderingDefaults = rs.getObject("rendering_defaults_version") as Int?) }
            .list()
        val draft = drafts.draft(handle, catalog) ?: return@withHandle releases.firstOrNull()
        // A draft that recorded no base (opened before drafts did) is measured against the latest release.
        val base = draft.base?.takeIf { base -> releases.any { it.version == base } }
            ?: CatalogDrafts.latest(releases.map { it.version })
        releases.firstOrNull { it.version == base }
    }

    private fun entriesOf(catalog: CatalogId, version: String) = jdbi
        .withHandle<Map<String, ReleaseResource>, Exception> { handle ->
            releaseResourceStore.entriesOf(handle, catalog, version).associateBy { it.contentKey }
        }
}

/** The setting entry of a change of rendering defaults ([GetCatalogResourceChanges]). */
const val RENDERING_DEFAULTS_ENTRY = "catalog/renderingDefaults"

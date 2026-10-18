// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.ready.ReadyMarks
import app.epistola.suite.catalog.revisions.ReleaseEntry
import app.epistola.suite.catalog.revisions.ReleaseEntryStore
import app.epistola.suite.catalog.versions.CatalogDrafts
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
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
 * What the next release of an AUTHORED catalog would contain that the last one did not — resource
 * by resource.
 *
 * [GetCatalogReleaseStatus] answers *whether* the working copy has drifted from the release; this
 * answers *where*. Both compare the same canonical content, so they cannot disagree: a per-resource
 * digest difference is exactly a catalog fingerprint difference localized to one resource.
 *
 * O(catalog-size), like the drift check it refines — it builds the working copy once. Read it for a
 * page about one catalog, not for a list of them.
 */
data class GetCatalogResourceChanges(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogVersionRef? = null,
) : CatalogVersionScoped,
    Query<CatalogResourceChanges>,
    RequiresPermission {
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

    /** Changed or not — the last release recorded no per-resource baseline to compare against. */
    UNKNOWN,
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
) {
    /** Whether releasing waits on this resource: it changed and is not ready. */
    val blocksRelease: Boolean get() = state in CHANGED && !ready

    private companion object {
        val CHANGED = setOf(CatalogResourceState.NEW, CatalogResourceState.MODIFIED, CatalogResourceState.UNKNOWN)
    }
}

data class CatalogResourceChanges(
    val catalogKey: CatalogKey,
    val latestVersion: String?,
    /**
     * False when the catalog has drifted from a release cut before releases recorded their
     * resources (V20260923201010), which cannot be reconstructed. Every resource is then
     * [CatalogResourceState.UNKNOWN]; the next release restores the detail.
     */
    val baselineAvailable: Boolean,
    /** Ordered by type then slug, the order the manifest lists them in. */
    val resources: List<CatalogResourceChange>,
) {
    val added: List<CatalogResourceChange> get() = inState(CatalogResourceState.NEW)
    val modified: List<CatalogResourceChange> get() = inState(CatalogResourceState.MODIFIED)
    val removed: List<CatalogResourceChange> get() = inState(CatalogResourceState.REMOVED)
    val released: List<CatalogResourceChange> get() = inState(CatalogResourceState.RELEASED)

    val hasUnreleasedChanges: Boolean get() = resources.any { it.state != CatalogResourceState.RELEASED }

    /** The changed resources not marked ready, which a release refuses. */
    val notReady: List<CatalogResourceChange> get() = resources.filter { it.blocksRelease }

    private fun inState(state: CatalogResourceState) = resources.filter { it.state == state }
}

@Component
class GetCatalogResourceChangesHandler(
    private val jdbi: Jdbi,
    private val contentBuilder: CatalogContentBuilder,
    private val fingerprintService: CatalogFingerprintService,
    private val releaseEntryStore: ReleaseEntryStore,
    private val readyMarks: ReadyMarks,
    private val drafts: CatalogDrafts,
) : QueryHandler<GetCatalogResourceChanges, CatalogResourceChanges> {

    override fun handle(query: GetCatalogResourceChanges): CatalogResourceChanges {
        val catalog = GetCatalog(query.tenantKey, query.catalogKey).query()
            ?: throw CatalogNotFoundException(query.catalogKey)
        // Only an authored catalog has a next release. A subscribed one is replaced wholesale by
        // its source, and `PreviewCatalogUpgrade` is the diff that applies to it.
        if (catalog.type != CatalogType.AUTHORED) throw CatalogReadOnlyException(query.catalogKey)

        val content = contentBuilder.build(query.tenantKey, query.catalogKey)
        val working = fingerprintService.perResourceFingerprints(content)
        val release = loadBaseRelease(query.tenantKey, query.catalogKey)
        val released = release?.let { entriesOf(query.tenantKey, query.catalogKey, it.version) }.orEmpty()
        val retainedVersions = release?.let {
            jdbi.withHandle<List<String>, Exception> { handle ->
                releaseEntryStore.retainedVersions(handle, query.tenantKey, query.catalogKey)
            }
        }.orEmpty()

        // The working copy names a resource it still has; the release names one dropped since.
        val names = content.resourceEntries.associate { "${it.type}/${it.key}" to it.name } +
            released.filterKeys { it !in working }.mapValues { (_, entry) -> entry.name }

        val readiness = jdbi.withHandle<Map<String, Boolean>, Exception> { readyMarks.readiness(it, query.tenantKey, query.catalogKey) }

        fun change(key: String, state: CatalogResourceState) = CatalogResourceChange(
            type = key.substringBefore('/'),
            slug = key.substringAfter('/'),
            name = names[key],
            state = state,
            ready = readiness[key] == true,
        )

        // Nothing released yet: every resource is new, and that is a complete answer.
        if (release == null) {
            return CatalogResourceChanges(
                catalogKey = query.catalogKey,
                latestVersion = null,
                baselineAvailable = true,
                resources = working.keys.sorted().map { change(it, CatalogResourceState.NEW) },
            )
        }

        val baseline = released.mapValues { (_, entry) -> entry.fingerprint }.takeIf { retainedVersions.contains(release.version) }
            // A release cut before V20260923201010 recorded no resources. When the working copy
            // still matches its fingerprint the content is identical by definition, so the working
            // digests *are* that release's digests and the answer is exact without them.
            ?: if (fingerprintService.matchesFingerprint(content, release.fingerprint)) {
                working
            } else {
                return CatalogResourceChanges(
                    catalogKey = query.catalogKey,
                    latestVersion = release.version,
                    baselineAvailable = false,
                    resources = working.keys.sorted().map { change(it, CatalogResourceState.UNKNOWN) },
                )
            }

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
            baselineAvailable = true,
            resources = resources,
        )
    }

    private data class ReleaseRow(val version: String, val fingerprint: String)

    /**
     * What the draft the caller means is compared against (contract ADR 0002): its base, the release it
     * started from, or, when it records none that the catalog still has, the highest release below its
     * number. Without a draft, the latest release.
     */
    private fun loadBaseRelease(tenantKey: TenantKey, catalogKey: CatalogKey): ReleaseRow? = jdbi.withHandle<ReleaseRow?, Exception> { handle ->
        val releases = handle.createQuery(
            """
            SELECT version, fingerprint
            FROM catalog_releases
            WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}
            $LATEST_RELEASE_ORDER
            """,
        )
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .map { rs, _ -> ReleaseRow(version = rs.getString("version"), fingerprint = rs.getString("fingerprint")) }
            .list()
        val draft = drafts.draft(handle, tenantKey, catalogKey) ?: return@withHandle releases.firstOrNull()
        // A draft that recorded no base (opened before drafts did) is measured against the latest release.
        val base = draft.base?.takeIf { base -> releases.any { it.version == base } }
            ?: CatalogDrafts.latest(releases.map { it.version })
        releases.firstOrNull { it.version == base }
    }

    private fun entriesOf(tenantKey: TenantKey, catalogKey: CatalogKey, version: String) = jdbi
        .withHandle<Map<String, ReleaseEntry>, Exception> { handle ->
            releaseEntryStore.entriesOf(handle, tenantKey, catalogKey, version).associateBy { it.contentKey }
        }
}

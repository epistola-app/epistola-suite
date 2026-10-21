// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.merge

import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.revisions.ReleaseEntryStore
import app.epistola.suite.catalog.versions.CatalogDrafts
import app.epistola.suite.catalog.versions.CatalogVersionNotFoundException
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * What merging [release] into a draft would do, resource by resource (ADR 0032, alpha): the draft
 * the query targets, or the catalog's next version. Compared against the merge base, the closest
 * release both descend from ([DraftMerge.mergeBase]), from the fingerprints the release snapshots
 * and the draft already have; nothing is changed.
 *
 * @throws CatalogVersionNotFoundException when the catalog has no such draft, or no such release.
 */
data class PreviewDraftMerge(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val release: String,
    /** The draft to merge into; null for the catalog's next version. */
    override val catalogVersion: CatalogVersionRef? = null,
) : CatalogVersionScoped,
    Query<DraftMergePreview>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

data class DraftMergePreview(
    val catalogKey: CatalogKey,
    /** What people call the draft ("Next release", or its name). */
    val draftLabel: String,
    /** The merge base: the closest release the draft and [release] both descend from; null when none. */
    val base: String?,
    val release: String,
    /** Whether the draft already contains [release]: its base, one merged before, or one either descends from. */
    val alreadyMerged: Boolean,
    /** Ordered by type, then key. */
    val resources: List<MergeResource>,
) {
    val conflicts: List<MergeResource> get() = resources.filter { it.outcome == MergeOutcome.CONFLICT }

    /** What the merge takes from the release without asking. */
    val taken: List<MergeResource> get() = resources.filter { it.outcome == MergeOutcome.THEIRS }

    /** Whether a merge changes the draft at all. */
    val changesDraft: Boolean get() = taken.isNotEmpty() || conflicts.isNotEmpty()
}

@Component
class PreviewDraftMergeHandler(
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
    private val entries: ReleaseEntryStore,
    private val contentBuilder: CatalogContentBuilder,
    private val fingerprints: CatalogFingerprintService,
) : QueryHandler<PreviewDraftMerge, DraftMergePreview> {

    override fun handle(query: PreviewDraftMerge): DraftMergePreview {
        val (tenantKey, catalogKey) = query.tenantKey to query.catalogKey
        // The draft the query targets: the mediator scopes it, so building the content reads it too.
        val draft = jdbi.withHandle<_, Exception> { drafts.draft(it, tenantKey, catalogKey) }
            ?: throw CatalogVersionNotFoundException(catalogKey, query.catalogVersion?.draft?.toString() ?: "next")
        val bases = jdbi.withHandle<Map<String, String?>, Exception> { releaseBases(it, tenantKey, catalogKey) }
        if (query.release !in bases) throw CatalogVersionNotFoundException(catalogKey, query.release)
        // A release's base as it recorded it, else the highest release below it (releases recorded
        // before bases were, and installed ones, carry none).
        val baseOf = { release: String -> bases[release] ?: CatalogDrafts.highestBelow(release, bases.keys) }
        val contained = DraftMerge.contained(draft.base, draft.merged, baseOf)
        val mergeBase = DraftMerge.mergeBase(query.release, contained, baseOf)
        val (base, theirs) = jdbi.withHandle<Pair<List<app.epistola.suite.catalog.revisions.ReleaseEntry>, List<app.epistola.suite.catalog.revisions.ReleaseEntry>>, Exception> { handle ->
            mergeBase?.let { entries.entriesOf(handle, tenantKey, catalogKey, it) }.orEmpty() to entries.entriesOf(handle, tenantKey, catalogKey, query.release)
        }
        val content = contentBuilder.build(tenantKey, catalogKey)
        val ours = fingerprints.perResourceFingerprints(content)

        // A resource is called what the draft calls it, else what the release does, else the base.
        val names = base.associate { it.contentKey to it.name } +
            theirs.associate { it.contentKey to it.name } +
            content.resourceEntries.associate { "${it.type}/${it.key}" to it.name }

        return DraftMergePreview(
            catalogKey = catalogKey,
            draftLabel = draft.label,
            base = mergeBase,
            release = query.release,
            alreadyMerged = query.release in contained,
            resources = DraftMerge.classify(
                base = base.associate { it.contentKey to it.fingerprint },
                ours = ours,
                theirs = theirs.associate { it.contentKey to it.fingerprint },
                names = names,
            ),
        )
    }

    /** Every release of the catalog with the base it recorded (null when none). */
    private fun releaseBases(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): Map<String, String?> = handle
        .createQuery("SELECT version, base_version FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .map { rs, _ -> rs.getString("version") to rs.getString("base_version") }
        .list()
        .toMap()
}

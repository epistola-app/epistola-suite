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
 * the query targets, or the catalog's next version. Compared against the draft's base, from the
 * fingerprints its release snapshots and the draft already have; nothing is changed.
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
    /** What people call the draft ("Next version", or its name). */
    val draftLabel: String,
    val base: String?,
    val release: String,
    /** Whether the draft already contains [release]: its base, or merged before. */
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
        val (base, theirs) = jdbi.withHandle<Pair<List<app.epistola.suite.catalog.revisions.ReleaseEntry>, List<app.epistola.suite.catalog.revisions.ReleaseEntry>>, Exception> { handle ->
            if (!hasRelease(handle, tenantKey, catalogKey, query.release)) throw CatalogVersionNotFoundException(catalogKey, query.release)
            val base = draft.base?.let { entries.entriesOf(handle, tenantKey, catalogKey, it) }.orEmpty()
            base to entries.entriesOf(handle, tenantKey, catalogKey, query.release)
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
            base = draft.base,
            release = query.release,
            alreadyMerged = query.release == draft.base || query.release in draft.merged,
            resources = DraftMerge.classify(
                base = base.associate { it.contentKey to it.fingerprint },
                ours = ours,
                theirs = theirs.associate { it.contentKey to it.fingerprint },
                names = names,
            ),
        )
    }

    private fun hasRelease(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String): Boolean = handle
        .createQuery("SELECT 1 FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :v")
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .bind("v", version)
        .mapTo(Int::class.java)
        .findOne()
        .isPresent
}

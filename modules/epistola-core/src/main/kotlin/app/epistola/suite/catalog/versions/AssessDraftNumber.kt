// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.queries.ListCatalogReleases
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
 * The number a draft would be released as (contract ADR 0002, amendment 2): numbered from the kind of
 * change it holds against the catalog's latest release, by ADR 0001's rule of what breaks. Breaking
 * changes raise the major, additions the minor, anything else the patch. A catalog never released
 * starts at [CatalogDrafts.FIRST].
 *
 * The author may choose a higher number, never a lower one ([DraftNumberAssessment.refuses]).
 */
data class AssessDraftNumber(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val draft: DraftRef = DraftRef.Next,
) : Query<DraftNumberAssessment?>,
    CatalogVersionScoped,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW

    /** The draft itself: its content is what its changes are measured on. */
    override val catalogVersion: CatalogVersionRef get() = CatalogVersionRef(tenantKey, catalogKey, draft)
}

/**
 * @property latest the catalog's latest release, which the draft is measured against; null when it was
 *   never released.
 * @property needs the kind of change the draft holds against [latest]; null when there is nothing to
 *   compare with (never released), so any higher number fits.
 * @property reasons what makes the change [needs], breaking first; empty for a fix.
 * @property suggested the lowest number that fits.
 */
data class DraftNumberAssessment(
    val latest: String?,
    val needs: ChangeKind?,
    val reasons: List<String>,
    val suggested: String,
) {
    /**
     * Why [version] cannot be the release's number, or null when it can: it must be a version, above
     * [latest], and no lower than [suggested] when the changes say what they need.
     */
    fun refuses(version: String): String? {
        val wanted = SemVer.parseOrNull(version) ?: return "Version '$version' must be MAJOR.MINOR.PATCH"
        val floor = latest?.let(SemVer::parseOrNull) ?: return null
        if (wanted <= floor) return "Version $wanted must be greater than the latest release $floor"
        val step = VersionStep.between(floor, wanted) ?: return null
        if (needs != null && needs > step.allows) {
            return "Version $wanted is too low for what changed since $floor: it needs at least $suggested (" + reasons.joinToString("; ") + ")"
        }
        return null
    }
}

@Component
class AssessDraftNumberHandler(
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
    private val changeKinds: DraftChangeKinds,
) : QueryHandler<AssessDraftNumber, DraftNumberAssessment?> {
    override fun handle(query: AssessDraftNumber): DraftNumberAssessment? {
        jdbi.withHandle<CatalogDraft?, Exception> { drafts.find(it, query.tenantKey, query.catalogKey, query.draft) } ?: return null
        val released = ListCatalogReleases(query.tenantKey, query.catalogKey).query().map { it.version }
        val latest = CatalogDrafts.latest(released) ?: return DraftNumberAssessment(null, null, emptyList(), CatalogDrafts.FIRST)
        val latestVersion = SemVer.parse(latest)
        val changes = changeKinds.against(query.tenantKey, query.catalogKey, latest)
            ?: return DraftNumberAssessment(latest, null, emptyList(), latestVersion.bumpPatch().toString())
        val suggested = when (changes.kind) {
            ChangeKind.BREAKING -> latestVersion.bumpMajor()
            ChangeKind.ADDITIVE -> latestVersion.bumpMinor()
            ChangeKind.NONE -> latestVersion.bumpPatch()
        }
        return DraftNumberAssessment(
            latest = latest,
            needs = changes.kind,
            reasons = changes.above(ChangeKind.NONE).map { it.toString() },
            suggested = suggested.toString(),
        )
    }
}

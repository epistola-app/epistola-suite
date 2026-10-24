// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
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
 * The number a draft would be released as (contract ADR 0002, "Releasing any draft"): from the kind
 * of change it holds against its base, by ADR 0001's rule of what breaks. Breaking changes raise the
 * major, additions the minor, anything else the patch; a draft without a base needs
 * [CatalogDrafts.FIRST].
 *
 * The number must be above the base and not taken. When the one the changes need is taken, the draft
 * gets the next free number of the same kind (from `1.4.0` while `2.0.0` exists, a breaking change
 * is `3.0.0`). The author may choose a higher number, never a lower one ([DraftNumberAssessment.refuses]).
 */
data class AssessDraftNumber(
    val catalog: CatalogId,
    val draft: DraftRef = DraftRef.Next,
) : Query<DraftNumberAssessment?>,
    CatalogReleaseScoped,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.CATALOG_VIEW

    /** The draft itself: its content is what its changes are measured on. */
    override val catalogVersion: CatalogReleaseRef get() = CatalogReleaseRef(catalog, draft)
}

/**
 * @property base the draft's base, which it is measured against; null when it has none.
 * @property needs the kind of change the draft holds against [base]; null when there is nothing to
 *   compare with (no base, or the base kept no content).
 * @property reasons what makes the change [needs], breaking first; empty for a fix.
 * @property needed the number the changes need: [base] raised by [needs], or [CatalogDrafts.FIRST].
 * @property suggested [needed], or the next free number of the same kind when a release has it.
 * @property taken the numbers the catalog's releases have.
 */
data class DraftNumberAssessment(
    val base: String?,
    val needs: ChangeKind?,
    val reasons: List<String>,
    val needed: String,
    val suggested: String,
    val taken: Set<String> = emptySet(),
) {
    /**
     * Why [version] cannot be the release's number, or null when it can: it must be a version no
     * release has, above [base], and no lower than [needed].
     */
    fun refuses(version: String): String? {
        val wanted = SemVer.parseOrNull(version) ?: return "Version '$version' must be MAJOR.MINOR.PATCH"
        // A patch release (`1.3.1-gemeente-x.1`) is received, not issued here yet: who may issue which
        // patch number is ADR 0034's phase 5.
        if (wanted.isPatchRelease) return "Version $wanted is a patch release number, which this installation receives but does not issue yet: give MAJOR.MINOR.PATCH"
        if (wanted.toString() in taken) return "Version $wanted is released already"
        base?.let(SemVer::parseOrNull)?.let { floor ->
            if (wanted <= floor) return "Version $wanted must be greater than its base $floor"
        }
        val floor = SemVer.parse(needed)
        if (wanted < floor) {
            val since = base?.let { " since $it" }.orEmpty()
            return "Version $wanted is too low for what changed$since: it needs at least $needed" + reasons.takeIf { it.isNotEmpty() }?.joinToString("; ", " (", ")").orEmpty()
        }
        return null
    }

    companion object {
        /** [needed], or the next number of the same [kind] that no release has. */
        fun nextFree(needed: SemVer, kind: ChangeKind, taken: Set<String>): SemVer = generateSequence(needed) { previous ->
            when (kind) {
                ChangeKind.BREAKING -> previous.bumpMajor()
                ChangeKind.ADDITIVE -> previous.bumpMinor()
                ChangeKind.NONE -> previous.bumpPatch()
            }
        }.first { it.toString() !in taken }
    }
}

@Component
class AssessDraftNumberHandler(
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
    private val changeKinds: DraftChangeKinds,
) : QueryHandler<AssessDraftNumber, DraftNumberAssessment?> {
    override fun handle(query: AssessDraftNumber): DraftNumberAssessment? {
        val draft = jdbi.withHandle<CatalogDraft?, Exception> { drafts.find(it, query.catalog, query.draft) } ?: return null
        val taken = ListCatalogReleaseSummaries(query.catalog).query().map { it.version }.toSet()
        val base = draft.base
        if (base == null) {
            // Nothing to measure against: the first number, or the next free major after it.
            val first = SemVer.parse(CatalogDrafts.FIRST)
            return DraftNumberAssessment(null, null, emptyList(), first.toString(), DraftNumberAssessment.nextFree(first, ChangeKind.BREAKING, taken).toString(), taken)
        }
        val baseVersion = SemVer.parse(base)
        val changes = changeKinds.against(query.catalog, base)
        val kind = changes?.kind ?: ChangeKind.NONE
        val needed = when (kind) {
            ChangeKind.BREAKING -> baseVersion.bumpMajor()
            ChangeKind.ADDITIVE -> baseVersion.bumpMinor()
            ChangeKind.NONE -> baseVersion.bumpPatch()
        }
        return DraftNumberAssessment(
            base = base,
            needs = changes?.kind,
            reasons = changes?.above(ChangeKind.NONE).orEmpty().map { it.toString() },
            needed = needed.toString(),
            suggested = DraftNumberAssessment.nextFree(needed, kind, taken).toString(),
            taken = taken,
        )
    }
}

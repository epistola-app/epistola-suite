// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.catalog.SemVer
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.springframework.stereotype.Component

/**
 * Whether draft [version]'s own number is as big a step from its base as its changes need (contract
 * ADR 0002, by ADR 0001's rule of what breaks): breaking changes need a major, additions at least a
 * minor.
 *
 * Renumbering warns about a number that is too low; this says the same of the number a draft already
 * has, which matters most for the draft a release opens automatically on the next patch: releasing it
 * as it is would ship breaking changes or additions under a patch number.
 *
 * Null when there is nothing to judge: the version is not a draft, it has no base, its number is not
 * above its base, or the base release kept no content to compare with.
 */
data class AssessDraftNumber(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
) : Query<DraftNumberAssessment?>,
    CatalogVersionScoped,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW

    /** The draft itself: its content is what its changes are measured on. */
    override val catalogVersion: CatalogVersionRef get() = CatalogVersionRef(tenantKey, catalogKey, version)
}

/**
 * @property step how far [version] is from [base].
 * @property needs the kind of change the draft holds against its base.
 * @property reasons what makes [needs] bigger than [step] allows, breaking first; empty when it fits.
 * @property suggested the lowest number above [base] that fits [needs].
 */
data class DraftNumberAssessment(
    val version: String,
    val base: String,
    val step: VersionStep,
    val needs: ChangeKind,
    val reasons: List<String>,
    val suggested: String,
) {
    /** The draft's number is a smaller step than its changes need. */
    val tooLow: Boolean get() = needs > step.allows
}

@Component
class AssessDraftNumberHandler(
    private val changeKinds: DraftChangeKinds,
) : QueryHandler<AssessDraftNumber, DraftNumberAssessment?> {
    override fun handle(query: AssessDraftNumber): DraftNumberAssessment? {
        val draft = GetCatalogVersion(query.tenantKey, query.catalogKey, query.version).query() ?: return null
        if (draft.status != CatalogVersionStatus.DRAFT) return null
        val base = draft.base ?: return null
        val baseVersion = SemVer.parseOrNull(base) ?: return null
        val step = SemVer.parseOrNull(draft.version)?.let { VersionStep.between(baseVersion, it) } ?: return null
        val changes = changeKinds.against(query.tenantKey, query.catalogKey, base) ?: return null
        val suggested = when (changes.kind) {
            ChangeKind.BREAKING -> baseVersion.bumpMajor()
            ChangeKind.ADDITIVE -> baseVersion.bumpMinor()
            ChangeKind.NONE -> baseVersion.bumpPatch()
        }
        return DraftNumberAssessment(
            version = draft.version,
            base = base,
            step = step,
            needs = changes.kind,
            reasons = changes.above(step.allows).map { it.toString() },
            suggested = suggested.toString(),
        )
    }
}

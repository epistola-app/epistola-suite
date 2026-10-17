// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.queries.CatalogResourceChange
import app.epistola.suite.catalog.queries.CatalogResourceChanges
import app.epistola.suite.catalog.queries.CatalogResourceState
import app.epistola.suite.catalog.queries.GetCatalogResourceChanges
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.versions.AssessDraftNumber
import app.epistola.suite.catalog.versions.DraftNumberAssessment
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.SecurityContext

/**
 * Where an authored catalog's next release stands, and which of its actions to offer: the draft's
 * number, what changed since the last release and whether each change is ready, and the declared
 * dependencies the release is cut with. With several drafts (contract ADR 0002) it is the release of
 * the draft the page works in, [drafts]`.chosen`.
 *
 * Built for one catalog's page (it builds the working copy once, like [GetCatalogResourceChanges]);
 * the release dialog and the catalog page both gate on the names below, so the two cannot disagree.
 *
 * @property mayRelease the viewer holds `CATALOG_RELEASE`, which releasing a draft needs.
 * @property mayEditDraft the viewer holds `TEMPLATE_EDIT`, which renumbering a draft and editing or
 *   resolving its dependencies need (contract `renumberCatalogVersion`, `updateCatalogDependencies`).
 * @property mayMarkReady the viewer holds `TEMPLATE_EDIT`, which marking resources ready needs.
 */
data class CatalogReleaseReadiness(
    val catalogKey: CatalogKey,
    /** The draft's version number; null for a catalog with no open draft. */
    val draftVersion: String?,
    /** Which draft this is, its siblings, and what may be done to them. */
    val drafts: CatalogDraftBar,
    val changes: CatalogResourceChanges,
    val dependencies: List<CatalogDependencyDeclaration>,
    val mayRelease: Boolean,
    val mayEditDraft: Boolean,
    val mayMarkReady: Boolean,
    /** Whether the draft's number is as big a step from its base as its changes need; null when there is nothing to judge. */
    val numbering: DraftNumberAssessment? = null,
) {
    /**
     * The draft's own number is a smaller step from its base than its changes need (breaking changes
     * under a patch or minor, additions under a patch). Releasing is not refused, as ADR 0002 makes
     * this a renumber warning only, but the page says so before the release, since the draft a release
     * opens is numbered as the next patch whatever it then holds.
     */
    val numberTooLow: Boolean get() = draftVersion != null && numbering?.tooLow == true

    /** The changed resources not marked ready; releasing refuses while there is one. */
    val notReady: List<CatalogResourceChange> get() = changes.notReady

    /** Every resource that differs from the last release, in manifest order. */
    val changed: List<CatalogResourceChange> get() = changes.resources.filter { it.state != CatalogResourceState.RELEASED }

    /**
     * The declarations that match no release yet, which releasing refuses
     * (`CATALOG_DEPENDENCY_UNRESOLVED`). A lock that stopped holding is caught by the release itself.
     */
    val unresolvedDependencies: List<CatalogDependencyDeclaration> get() = dependencies.filter { !it.locked }

    /**
     * Whether to offer Release: the viewer may release, and nothing blocks it. When the viewer may
     * but something blocks it, [releaseBlocked] says so and the page names what, instead of offering
     * a release that would be refused.
     */
    val offerRelease: Boolean get() = mayRelease && draftVersion != null && notReady.isEmpty() && unresolvedDependencies.isEmpty()

    /** The viewer may release, but a resource is not ready or a dependency is unresolved. */
    val releaseBlocked: Boolean get() = mayRelease && draftVersion != null && !offerRelease

    /** Whether to offer "Mark all ready": the viewer may mark, and a changed resource is not ready. */
    val offerMarkAllReady: Boolean get() = mayMarkReady && draftVersion != null && notReady.isNotEmpty()

    /** Whether to offer renumbering the draft. */
    val offerRenumber: Boolean get() = mayEditDraft && draftVersion != null

    /** Whether to offer "Resolve to latest": the viewer may, and there is a declaration to move. */
    val offerResolveDependencies: Boolean get() = mayEditDraft && draftVersion != null && dependencies.isNotEmpty()

    /** Whether to offer editing the declared dependencies: the viewer may, and there is a draft to declare them in. */
    val offerEditDependencies: Boolean get() = mayEditDraft && draftVersion != null

    companion object {
        /** Reads the readiness of the draft [drafts] works in, for the current principal. */
        fun of(drafts: CatalogDraftBar): CatalogReleaseReadiness {
            val tenantKey = drafts.tenantKey
            val catalogKey = drafts.catalogKey
            val auth = AuthContext.from(SecurityContext.current(), tenantKey)
            val draft = drafts.chosen?.version
            return CatalogReleaseReadiness(
                catalogKey = catalogKey,
                draftVersion = draft,
                drafts = drafts,
                // Without a draft there is nothing that changed, and nothing to declare dependencies in.
                changes = if (draft == null) {
                    CatalogResourceChanges(catalogKey, drafts.releases.firstOrNull()?.version, baselineAvailable = true, resources = emptyList())
                } else {
                    GetCatalogResourceChanges(tenantKey, catalogKey, catalogVersion = drafts.ref).query()
                },
                dependencies = if (draft == null) emptyList() else ListCatalogDependencies(tenantKey, catalogKey, catalogVersion = drafts.ref).query(),
                mayRelease = auth.has(Permission.CATALOG_RELEASE),
                mayEditDraft = auth.has(Permission.TEMPLATE_EDIT),
                mayMarkReady = auth.has(Permission.TEMPLATE_EDIT),
                numbering = draft?.let { AssessDraftNumber(tenantKey, catalogKey, it).query() },
            )
        }
    }
}

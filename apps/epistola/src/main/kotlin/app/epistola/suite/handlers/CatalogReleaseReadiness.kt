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
import app.epistola.suite.catalog.versions.CatalogVersionStatus
import app.epistola.suite.catalog.versions.ListCatalogVersions
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.SecurityContext

/**
 * Where an authored catalog's next release stands, and which of its actions to offer: the draft's
 * number, what changed since the last release and whether each change is ready, and the declared
 * dependencies the release is cut with.
 *
 * Built for one catalog's page (it builds the working copy once, like [GetCatalogResourceChanges]);
 * the release dialog and the catalog page both gate on the names below, so the two cannot disagree.
 *
 * @property mayRelease the viewer holds `TEMPLATE_PUBLISH`, which releasing, renumbering and
 *   declaring dependencies need.
 * @property mayMarkReady the viewer holds `TEMPLATE_EDIT`, which marking resources ready needs.
 */
data class CatalogReleaseReadiness(
    val catalogKey: CatalogKey,
    /** The open draft's version number; null only for a catalog that has none (never for an authored one). */
    val draftVersion: String?,
    val changes: CatalogResourceChanges,
    val dependencies: List<CatalogDependencyDeclaration>,
    val mayRelease: Boolean,
    val mayMarkReady: Boolean,
) {
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
    val offerRelease: Boolean get() = mayRelease && notReady.isEmpty() && unresolvedDependencies.isEmpty()

    /** The viewer may release, but a resource is not ready or a dependency is unresolved. */
    val releaseBlocked: Boolean get() = mayRelease && !offerRelease

    /** Whether to offer "Mark all ready": the viewer may mark, and a changed resource is not ready. */
    val offerMarkAllReady: Boolean get() = mayMarkReady && notReady.isNotEmpty()

    /** Whether to offer renumbering the draft. */
    val offerRenumber: Boolean get() = mayRelease && draftVersion != null

    /** Whether to offer "Resolve to latest": the viewer may, and there is a declaration to move. */
    val offerResolveDependencies: Boolean get() = mayRelease && dependencies.isNotEmpty()

    companion object {
        /** Reads the readiness of the AUTHORED catalog [catalogKey] for the current principal. */
        fun of(tenantKey: TenantKey, catalogKey: CatalogKey): CatalogReleaseReadiness {
            val auth = AuthContext.from(SecurityContext.current(), tenantKey)
            return CatalogReleaseReadiness(
                catalogKey = catalogKey,
                draftVersion = ListCatalogVersions(tenantKey, catalogKey, CatalogVersionStatus.DRAFT).query().firstOrNull()?.version,
                changes = GetCatalogResourceChanges(tenantKey, catalogKey).query(),
                dependencies = ListCatalogDependencies(tenantKey, catalogKey).query(),
                mayRelease = auth.has(Permission.TEMPLATE_PUBLISH),
                mayMarkReady = auth.has(Permission.TEMPLATE_EDIT),
            )
        }
    }
}

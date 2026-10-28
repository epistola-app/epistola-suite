// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey

/** Why a release a deployed release renders with cannot be served (contract `MissingReleaseDependencyReason`). */
enum class MissingDependencyReason {
    /** The tenant has the release with its content; the environment does not serve it. */
    NOT_DEPLOYED,

    /** The tenant has no such release: no catalog of that name, or not that release of it. */
    NOT_INSTALLED,

    /**
     * Deploying dependencies along was asked for, but the tenant does not have the release. This
     * installation does not fetch a release from its registry, so a release it does not have stays
     * unavailable.
     */
    UNAVAILABLE,
}

/**
 * A release of another catalog that a release renders with, which the environment does not serve.
 * Named as the pin names it: [catalogKey] is the catalog's key within [registry] and [namespace],
 * which is also its key in the tenant when the tenant has it.
 */
data class MissingReleaseDependency(
    val registry: String,
    val namespace: String,
    val catalogKey: String,
    val releaseVersion: String,
    val reason: MissingDependencyReason,
) {
    /** The tenant's catalog, for a dependency the tenant has (every reason but [MissingDependencyReason.NOT_INSTALLED]). */
    fun tenantCatalog(tenantKey: TenantKey): CatalogId = CatalogId(namespace, CatalogKey.of(catalogKey), TenantId(tenantKey))

    override fun toString(): String = "$registry/$namespace/$catalogKey@$releaseVersion"
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle

/**
 * A release cannot be deleted, or have its content forgotten, while something renders from it: an
 * environment that has it deployed, or another release that recorded it as a dependency.
 */
class ReleaseInUseException(
    val catalogKey: CatalogKey,
    val version: String,
    val environments: List<String>,
    val dependentReleases: List<String>,
) : RuntimeException(
    buildString {
        append("Release ${catalogKey.value}@$version is in use")
        if (environments.isNotEmpty()) append(": deployed to ${environments.joinToString { "'$it'" }}")
        if (dependentReleases.isNotEmpty()) {
            append(if (environments.isNotEmpty()) ", and " else ": ")
            append("recorded as a dependency of ${dependentReleases.joinToString()}")
        }
        append(".")
    },
)

/**
 * Refuses removing what a release renders from while something still renders from it.
 *
 * Checked by the commands that delete a release, forget its content and delete a catalog, inside
 * their transaction, so the refusal names the environments and releases instead of surfacing as a
 * foreign-key error at commit.
 */
object ReleaseInUse {

    fun requireUnused(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String) {
        val environments = handle.createQuery(
            """
            SELECT environment_key FROM environment_catalog_deployments
            WHERE tenant_key = :t AND catalog_key = :c AND version = :v
            ORDER BY environment_key
            """,
        )
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .bind("v", version)
            .mapTo(String::class.java)
            .list()
        val dependents = handle.createQuery(
            """
            SELECT catalog_key || '@' || version FROM release_dependencies
            WHERE tenant_key = :t AND dependency_catalog_key = :c AND dependency_version = :v
              AND catalog_key <> :c
            ORDER BY catalog_key, version
            """,
        )
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .bind("v", version)
            .mapTo(String::class.java)
            .list()
        if (environments.isNotEmpty() || dependents.isNotEmpty()) {
            throw ReleaseInUseException(catalogKey, version, environments, dependents)
        }
    }

    /** As [requireUnused], for every release of a catalog that is about to be deleted. */
    fun requireCatalogUnused(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey) {
        handle.createQuery("SELECT version FROM catalog_releases WHERE tenant_key = :t AND catalog_key = :c")
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .mapTo(String::class.java)
            .list()
            .forEach { requireUnused(handle, tenantKey, catalogKey, it) }
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle

/**
 * A release cannot be deleted, or have its content forgotten, while an environment has it deployed.
 */
class ReleaseInUseException(
    val catalogKey: CatalogKey,
    val version: String,
    val environments: List<String>,
) : RuntimeException(
    "Release ${catalogKey.value}@$version is in use: deployed to ${environments.joinToString { "'$it'" }}.",
)

/**
 * Refuses removing what a release renders from while something still renders from it.
 *
 * Checked by the commands that delete a release, forget its content and delete a catalog, inside
 * their transaction, so the refusal names the environments instead of surfacing as a foreign-key
 * error at commit.
 */
object ReleaseInUse {

    fun requireUnused(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String) {
        val environments = handle.createQuery(
            """
            SELECT environment_key FROM environment_catalog_deployments
            WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :v
            ORDER BY environment_key
            """,
        )
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .bind("v", version)
            .mapTo(String::class.java)
            .list()
        if (environments.isNotEmpty()) throw ReleaseInUseException(catalogKey, version, environments)
    }

    /** As [requireUnused], for every release of a catalog that is about to be deleted. */
    fun requireCatalogUnused(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey) {
        handle.createQuery("SELECT version FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .mapTo(String::class.java)
            .list()
            .forEach { requireUnused(handle, tenantKey, catalogKey, it) }
    }
}

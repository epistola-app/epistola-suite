// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle

/**
 * A release cannot be deleted, or have its content forgotten, while an environment has it deployed
 * or a release of another catalog that kept its content is pinned to it (contract ADR 0003).
 */
class ReleaseInUseException(
    val catalogKey: CatalogKey,
    val version: String,
    val environments: List<String>,
    /** The releases pinned to it, as `catalog@version`. */
    val pinnedBy: List<String> = emptyList(),
) : RuntimeException(
    "Release ${catalogKey.value}@$version is in use: " +
        listOfNotNull(
            environments.takeIf { it.isNotEmpty() }?.let { "deployed to ${it.joinToString { environment -> "'$environment'" }}" },
            pinnedBy.takeIf { it.isNotEmpty() }?.let { "pinned by ${it.joinToString()}" },
        ).joinToString("; ") + ".",
)

/**
 * Refuses removing what a release renders from while something still renders from it.
 *
 * Checked by the commands that delete a release, forget its content and delete a catalog, inside
 * their transaction, so the refusal names the environments and pinning releases instead of
 * surfacing as a foreign-key error at commit, or as a release that no longer renders.
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
        // A release renders with the releases it pinned, so it keeps them while it can be rendered.
        val pinnedBy = handle.createQuery(
            """
            SELECT pinning.catalog_key || '@' || d.version FROM catalog_release_dependencies d
            JOIN catalog_releases r ON r.tenant_key = d.tenant_key AND r.catalog_id = d.catalog_id AND r.version = d.version
            JOIN catalogs pinning ON pinning.tenant_key = d.tenant_key AND pinning.catalog_id = d.catalog_id
            JOIN catalogs target ON target.tenant_key = d.tenant_key AND target.registry = d.registry
                                AND target.namespace = d.namespace AND target.catalog_key = d.dependency_key
            WHERE d.tenant_key = :t AND target.catalog_key = :c AND d.release_version = :v AND r.content_retained
            ORDER BY 1
            """,
        )
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .bind("v", version)
            .mapTo(String::class.java)
            .list()
        if (environments.isNotEmpty() || pinnedBy.isNotEmpty()) throw ReleaseInUseException(catalogKey, version, environments, pinnedBy)
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

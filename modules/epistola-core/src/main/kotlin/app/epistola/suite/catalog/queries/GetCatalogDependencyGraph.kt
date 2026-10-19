// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/** A catalog in the dependency graph. */
data class CatalogGraphNode(
    val catalogKey: String,
    val name: String,
    val type: CatalogType,
    /** `registry/namespace/key`: how a dependency names it. */
    val identity: String,
)

/**
 * One catalog depending on another, as the depending catalog's current version declares it.
 *
 * @property to the depended-on catalog's key in this tenant, or null when the tenant does not have it.
 * @property toIdentity how the declaration names it (`registry/namespace/key`), shown when [to] is null.
 * @property selector the version range the declaration allows, e.g. `^1.0.0` or `*`.
 * @property release the release the declaration is tied to, or null while it is not.
 */
data class CatalogGraphEdge(
    val from: String,
    val to: String?,
    val toIdentity: String,
    val selector: String,
    val release: String?,
)

data class CatalogDependencyGraph(val nodes: List<CatalogGraphNode>, val edges: List<CatalogGraphEdge>)

/**
 * The catalogs of a tenant and which depends on which (contract ADR 0003): each catalog's current
 * version's declared dependencies -- an authored catalog's next version, a subscribed one's installed
 * release. Two statements, ordered in the database.
 */
data class GetCatalogDependencyGraph(
    override val tenantKey: TenantKey,
) : Query<CatalogDependencyGraph>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class GetCatalogDependencyGraphHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetCatalogDependencyGraph, CatalogDependencyGraph> {
    override fun handle(query: GetCatalogDependencyGraph): CatalogDependencyGraph = jdbi.withHandle<CatalogDependencyGraph, Exception> { handle ->
        val nodes = handle.createQuery(
            """
            SELECT catalog_key, name, type, registry || '/' || namespace || '/' || catalog_key AS identity
            FROM catalogs WHERE tenant_key = :t
            ORDER BY name, catalog_key
            """,
        )
            .bind("t", query.tenantKey)
            .map { rs, _ -> CatalogGraphNode(rs.getString("catalog_key"), rs.getString("name"), CatalogType.valueOf(rs.getString("type")), rs.getString("identity")) }
            .list()
        val edges = handle.createQuery(
            """
            SELECT owner.catalog_key AS from_key, target.catalog_key AS to_key,
                   d.registry || '/' || d.namespace || '/' || d.dependency_key AS to_identity,
                   d.version_selector, d.release_version
            FROM catalog_dependencies d
            JOIN catalog_current_versions cv ON cv.tenant_key = d.tenant_key AND cv.version_id = d.version_id
            JOIN catalogs owner ON owner.tenant_key = d.tenant_key AND owner.catalog_id = d.catalog_id
            LEFT JOIN catalogs target ON target.tenant_key = d.tenant_key AND target.registry = d.registry
                                     AND target.namespace = d.namespace AND target.catalog_key = d.dependency_key
            WHERE d.tenant_key = :t
            ORDER BY owner.catalog_key, d.position
            """,
        )
            .bind("t", query.tenantKey)
            .map { rs, _ ->
                CatalogGraphEdge(rs.getString("from_key"), rs.getString("to_key"), rs.getString("to_identity"), rs.getString("version_selector"), rs.getString("release_version"))
            }
            .list()
        CatalogDependencyGraph(nodes, edges)
    }
}

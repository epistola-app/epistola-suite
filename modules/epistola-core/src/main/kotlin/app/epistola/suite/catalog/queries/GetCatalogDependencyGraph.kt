// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/** A catalog in the dependency graph. Two can share a key; [namespace] tells them apart (ADR 0034). */
data class CatalogGraphNode(
    val catalogKey: String,
    val name: String,
    /** `registry/namespace/key`: how a dependency names it. */
    val identity: String,
    val namespace: String = "",
)

/**
 * One catalog depending on another, as the depending catalog's current version declares it.
 *
 * @property to the depended-on catalog's key in this tenant, or null when the tenant does not have it.
 * @property toIdentity how the declaration names it (`registry/namespace/key`), shown when [to] is null.
 * @property release the release the declaration pins, or null while it pins none.
 */
data class CatalogGraphEdge(
    val from: String,
    val to: String?,
    val toIdentity: String,
    val release: String?,
    /** The namespaces of [from] and [to], which tell two catalogs sharing a key apart (ADR 0034). */
    val fromNamespace: String = "",
    val toNamespace: String? = null,
)

data class CatalogDependencyGraph(val nodes: List<CatalogGraphNode>, val edges: List<CatalogGraphEdge>)

/**
 * The catalogs of a tenant and which depends on which (contract ADR 0003): each catalog's current
 * version's declared dependencies (its next version, ADR 0032). Two statements, ordered in the
 * database.
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
            SELECT catalog_key, namespace, name, registry || '/' || namespace || '/' || catalog_key AS identity
            FROM catalogs WHERE tenant_key = :t
            ORDER BY name, catalog_key, namespace
            """,
        )
            .bind("t", query.tenantKey)
            .map { rs, _ -> CatalogGraphNode(rs.getString("catalog_key"), rs.getString("name"), rs.getString("identity"), rs.getString("namespace")) }
            .list()
        val edges = handle.createQuery(
            """
            SELECT owner.catalog_key AS from_key, owner.namespace AS from_namespace, target.catalog_key AS to_key, target.namespace AS to_namespace,
                   d.registry || '/' || d.namespace || '/' || d.dependency_key AS to_identity,
                   d.release_version
            FROM catalog_version_dependencies d
            JOIN catalog_current_versions cv ON cv.tenant_key = d.tenant_key AND cv.version_id = d.version_id
            JOIN catalogs owner ON owner.tenant_key = d.tenant_key AND owner.catalog_id = d.catalog_id
            LEFT JOIN catalogs target ON target.tenant_key = d.tenant_key AND target.registry = d.registry
                                     AND target.namespace = d.namespace AND target.catalog_key = d.dependency_key
            WHERE d.tenant_key = :t
            ORDER BY owner.catalog_key, owner.namespace, d.position
            """,
        )
            .bind("t", query.tenantKey)
            .map { rs, _ ->
                CatalogGraphEdge(rs.getString("from_key"), rs.getString("to_key"), rs.getString("to_identity"), rs.getString("release_version"), rs.getString("from_namespace"), rs.getString("to_namespace"))
            }
            .list()
        CatalogDependencyGraph(nodes, edges)
    }
}

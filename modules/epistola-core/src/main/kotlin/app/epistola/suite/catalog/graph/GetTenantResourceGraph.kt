// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.graph

import app.epistola.suite.common.ids.ResourceIdentity
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.transaction.TransactionIsolationLevel
import org.springframework.stereotype.Component

/** What uses what in [tenantKey]'s catalogs, as each catalog's current version holds it. */
data class GetTenantResourceGraph(
    override val tenantKey: TenantKey,
) : Query<TenantResourceGraph>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class GetTenantResourceGraphHandler(
    private val jdbi: Jdbi,
    private val builder: TenantResourceGraphBuilder,
) : QueryHandler<GetTenantResourceGraph, TenantResourceGraph> {
    override fun handle(query: GetTenantResourceGraph): TenantResourceGraph = jdbi.inTransaction<TenantResourceGraph, Exception>(TransactionIsolationLevel.REPEATABLE_READ) { handle ->
        builder.buildOn(handle, query.tenantKey)
    }
}

/**
 * Builds the tenant resource graph on a caller-supplied handle.
 *
 * Separate from the query handler because relocation needs the graph too — to decide whether a move
 * would leave the catalog dependency graph cyclic — and must see exactly the rows its own
 * transaction sees, including the ones it is about to rewrite. Dispatching the query through the
 * mediator would open a nested transaction with its own isolation level for a decision that has to
 * be part of the move, and importing the handler directly is what `DomainBoundaryTest` forbids.
 */
@Component
class TenantResourceGraphBuilder(
    private val reader: app.epistola.suite.catalog.revisions.VersionResourceReader,
) {
    /**
     * The graph from the reference index (`resource_references`, ADR 0033): the resources of each
     * catalog's current version, and what their stored contents refer to, parts counted as their
     * template's. One read of the index rather than a scan of every content: what a content refers to
     * was found once, when it was stored, by the same authority ([ResourceReferenceSites] and each
     * type's own references).
     */
    fun buildOn(handle: Handle, tenantKey: TenantKey): TenantResourceGraph {
        val nodes = loadNodes(handle, tenantKey)
        val occurrences = reader.currentReferences(handle, tenantKey).mapNotNull { held ->
            val type = CatalogResourceType.entries.firstOrNull { it.wireName == held.referencedType } ?: return@mapNotNull null
            val source = ResourceAddress.at(resourceType(held.type), held.catalogAddress, held.key)
            val qualification = when {
                held.alias != null -> ReferenceQualification.EXPLICIT

                // Resolved tenant-wide when unqualified: an image's key, a variant's bare attribute key.
                type == CatalogResourceType.IMAGE || type == CatalogResourceType.ATTRIBUTE -> ReferenceQualification.TENANT_GLOBAL

                else -> ReferenceQualification.RELATIVE
            }
            val catalog = if (qualification == ReferenceQualification.RELATIVE) source.catalogKey else held.alias
            Occurrence(source, ReferenceSelector(type, catalog, held.referencedKey), qualification)
        }
        return TenantResourceGraph(nodes, resolveAndAggregate(nodes, occurrences, loadAliases(handle, tenantKey)))
    }

    /** Every resource of each catalog's current version, read generically from its rows. */
    private fun loadNodes(handle: Handle, tenantKey: TenantKey): List<ResourceNode> = handle.createQuery(
        """
        SELECT r.resource_id, r.resource_type, c.namespace, c.catalog_key::text AS catalog_key, r.resource_key, r.name AS resource_name, c.name AS catalog_name
        FROM release_wire_resources r
        JOIN catalog_current_versions cv ON cv.tenant_key = r.tenant_key AND cv.version_id = r.version_id
        JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
        WHERE r.tenant_key = :tenantKey
        ORDER BY c.name, r.resource_type, r.name
        """,
    )
        .bind("tenantKey", tenantKey)
        .map { rs, _ ->
            ResourceNode(
                resourceId = ResourceIdentity.of(rs.getString("resource_id")),
                address = ResourceAddress(resourceType(rs.getString("resource_type")), rs.getString("namespace"), rs.getString("catalog_key"), rs.getString("resource_key")),
                name = rs.getString("resource_name"),
                catalogName = rs.getString("catalog_name"),
            )
        }.list()

    /**
     * What each alias means in each catalog's current version, by the catalog's dependency rows:
     * `(from address, alias)` to the target's address. The graph resolves an aliased reference with
     * it, as [app.epistola.suite.catalog.CatalogReferences] does (ADR 0034, section 9).
     */
    fun loadAliases(handle: Handle, tenantKey: TenantKey): Map<Pair<String, String>, String> = handle.createQuery(
        """
            SELECT c.namespace, c.catalog_key::text AS catalog_key, d.alias, d.namespace AS dependency_namespace, d.dependency_key
            FROM catalog_version_dependencies d
            JOIN catalog_current_versions cv ON cv.tenant_key = d.tenant_key AND cv.version_id = d.version_id
            JOIN catalogs c ON c.tenant_key = cv.tenant_key AND c.catalog_id = cv.catalog_id
            WHERE d.tenant_key = :tenantKey
        """,
    ).bind("tenantKey", tenantKey).map { rs, _ ->
        ("${rs.getString("namespace")}/${rs.getString("catalog_key")}" to rs.getString("alias")) to "${rs.getString("dependency_namespace")}/${rs.getString("dependency_key")}"
    }.list().toMap()

    private fun resolveAndAggregate(
        nodes: List<ResourceNode>,
        occurrences: List<Occurrence>,
        aliases: Map<Pair<String, String>, String>,
    ): List<ResourceEdge> {
        val byAddress = nodes.associateBy { it.address }
        val byTypeAndKey = nodes.groupBy { it.address.type to it.address.key }
        return occurrences.groupBy { occurrence ->
            val alias = occurrence.selector.catalogKey
            // The catalog the reference names: its alias from the source's catalog (its own key is
            // itself), else, undeclared, every catalog with that key.
            val target = alias?.let { if (it == occurrence.source.catalogKey) occurrence.source.catalogAddress else aliases[occurrence.source.catalogAddress to it] }
            val candidates = when {
                target != null -> byAddress[
                    ResourceAddress(occurrence.selector.type, target.substringBefore('/'), target.substringAfter('/'), occurrence.selector.key),
                ]?.let(::listOf).orEmpty()

                alias != null -> byTypeAndKey[occurrence.selector.type to occurrence.selector.key].orEmpty().filter { it.address.catalogKey == alias }

                else -> byTypeAndKey[occurrence.selector.type to occurrence.selector.key].orEmpty()
            }.map { it.address }.sortedBy { it.id }
            val resolution = when (candidates.size) {
                0 -> ReferenceResolution.MISSING
                1 -> ReferenceResolution.RESOLVED
                else -> ReferenceResolution.AMBIGUOUS
            }
            ResolvedKey(
                source = occurrence.source,
                selector = occurrence.selector,
                qualification = occurrence.qualification,
                candidates = candidates,
                resolution = resolution,
            )
        }.keys.map { key ->
            val id = listOf(key.source.id, key.selector.type.wireName, key.selector.catalogKey.orEmpty(), key.selector.key).joinToString("|")
            ResourceEdge(id, key.source, key.candidates.singleOrNull(), key.selector, key.candidates, key.qualification, key.resolution)
        }.sortedBy { it.id }
    }

    private fun resourceType(value: String): CatalogResourceType = CatalogResourceType.entries.single { it.wireName == value }

    private data class Occurrence(
        val source: ResourceAddress,
        val selector: ReferenceSelector,
        val qualification: ReferenceQualification,
    )

    private data class ResolvedKey(
        val source: ResourceAddress,
        val selector: ReferenceSelector,
        val qualification: ReferenceQualification,
        val candidates: List<ResourceAddress>,
        val resolution: ReferenceResolution,
    )
}

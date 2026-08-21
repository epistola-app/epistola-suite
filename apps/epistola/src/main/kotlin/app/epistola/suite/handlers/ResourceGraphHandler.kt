// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.GetTenantResourceGraph
import app.epistola.suite.catalog.graph.ReferenceSemantics
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.graph.ResourceEdge
import app.epistola.suite.catalog.graph.ResourceNode
import app.epistola.suite.catalog.graph.TenantResourceGraph
import app.epistola.suite.catalog.graph.TraversalDirection
import app.epistola.suite.catalog.graph.traverse
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.queries.GetTenant
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class ResourceGraphHandler {
    fun page(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantKey()
        return ServerResponse.ok().render(
            "layout/shell",
            mapOf(
                "contentView" to "resource-graph/index",
                "pageTitle" to "Resource graph - Epistola",
                "tenantId" to tenantKey.value,
                "tenant" to GetTenant(tenantKey).query(),
                "activeNavSection" to "resource-graph",
            ),
        )
    }

    fun nodes(request: ServerRequest): ServerResponse {
        val graph = loadGraph(request)
        val search = request.param("q").orElse("").trim().lowercase()
        val catalog = request.param("catalog").orElse("").trim()
        val type = request.param("type").orElse("").trim()
        val matches = graph.nodes.asSequence()
            .filter { search.isBlank() || it.name.lowercase().contains(search) || it.address.key.lowercase().contains(search) || it.catalogName.lowercase().contains(search) }
            .filter { catalog.isBlank() || it.address.catalogKey == catalog }
            .filter { type.isBlank() || it.address.type.wireName == type }
            .toList()
        val nodes = matches.asSequence()
            .take(50)
            .map(::nodeDto)
            .toList()
        val catalogs = graph.nodes.distinctBy { it.address.catalogKey }.map {
            mapOf("key" to it.address.catalogKey, "name" to it.catalogName)
        }.sortedBy { it["name"] }
        return json(mapOf("nodes" to nodes, "total" to matches.size, "catalogs" to catalogs))
    }

    fun subgraph(request: ServerRequest): ServerResponse {
        val type = resourceType(request.requiredParam("type"))
        val focus = ResourceAddress(type, request.requiredParam("catalog"), request.requiredParam("key"))
        val direction = runCatching { TraversalDirection.valueOf(request.param("direction").orElse("BOTH").uppercase()) }
            .getOrDefault(TraversalDirection.BOTH)
        val depth = request.param("depth").orElse("1").toIntOrNull()?.coerceIn(1, 3) ?: 1
        val graph = applyFilters(loadGraph(request), request, focus)
        if (graph.nodes.none { it.address == focus }) return ServerResponse.notFound().build()
        val result = graph.traverse(focus, direction, depth)
        return json(
            mapOf(
                "focus" to addressDto(result.focus),
                "nodes" to result.nodes.map(::nodeDto),
                "edges" to result.edges.map(::edgeDto),
            ),
        )
    }

    fun evidence(request: ServerRequest): ServerResponse {
        val edgeId = request.requiredParam("edgeId")
        val page = request.param("page").orElse("1").toIntOrNull()?.coerceAtLeast(1) ?: 1
        val edge = loadGraph(request).edges.singleOrNull { it.id == edgeId }
            ?: return ServerResponse.notFound().build()
        val pageSize = 25
        val start = ((page - 1) * pageSize).coerceAtMost(edge.evidence.size)
        val items = edge.evidence.drop(start).take(pageSize)
        return json(
            mapOf(
                "edge" to edgeDto(edge),
                "items" to items,
                "page" to page,
                "total" to edge.evidence.size,
                "totalPages" to maxOf(1, (edge.evidence.size + pageSize - 1) / pageSize),
            ),
        )
    }

    private fun loadGraph(request: ServerRequest) = GetTenantResourceGraph(
        tenantKey = request.tenantKey(),
        includeHistory = request.param("includeHistory").orElse("false").toBooleanStrictOrNull() ?: false,
    ).query()

    private fun applyFilters(graph: TenantResourceGraph, request: ServerRequest, focus: ResourceAddress): TenantResourceGraph {
        val catalogs = request.param("catalogs").orElse("").split(',').filter(String::isNotBlank).toSet()
        val types = request.param("types").orElse("").split(',').filter(String::isNotBlank).toSet()
        val semantics = request.param("semantics").orElse("").split(',').filter(String::isNotBlank).mapNotNull {
            runCatching { ReferenceSemantics.valueOf(it.uppercase()) }.getOrNull()
        }.toSet()
        val allowed = graph.nodes.filter { node ->
            (catalogs.isEmpty() || node.address.catalogKey in catalogs) &&
                (types.isEmpty() || node.address.type.wireName in types)
        }.map { it.address }.toMutableSet().apply { add(focus) }
        return TenantResourceGraph(
            nodes = graph.nodes.filter { it.address in allowed },
            edges = graph.edges.filter { edge ->
                edge.source in allowed &&
                    (edge.target == null || edge.target in allowed) &&
                    (semantics.isEmpty() || edge.semantics in semantics)
            },
        )
    }

    private fun nodeDto(node: ResourceNode) = mapOf(
        "id" to node.address.id,
        "type" to node.address.type.wireName,
        "catalogKey" to node.address.catalogKey,
        "key" to node.address.key,
        "name" to node.name,
        "catalogName" to node.catalogName,
        "catalogType" to node.catalogType,
    )

    private fun edgeDto(edge: ResourceEdge) = mapOf(
        "id" to edge.id,
        "source" to edge.source.id,
        "target" to edge.target?.id,
        "targetSelector" to mapOf("type" to edge.targetSelector.type.wireName, "catalogKey" to edge.targetSelector.catalogKey, "key" to edge.targetSelector.key),
        "targetCandidates" to edge.targetCandidates.map(::addressDto),
        "kind" to edge.kind,
        "semantics" to edge.semantics.name.lowercase(),
        "qualification" to edge.qualification.name.lowercase(),
        "resolution" to edge.resolution.name.lowercase(),
        "evidenceCount" to edge.evidenceCount,
    )

    private fun addressDto(address: ResourceAddress) = mapOf(
        "id" to address.id,
        "type" to address.type.wireName,
        "catalogKey" to address.catalogKey,
        "key" to address.key,
    )

    private fun resourceType(value: String) = CatalogResourceType.entries.singleOrNull { it.wireName == value }
        ?: throw IllegalArgumentException("Unknown resource type: $value")

    private fun ServerRequest.tenantKey() = TenantKey.of(pathVariable("tenantId"))
    private fun ServerRequest.requiredParam(name: String) = param(name).orElseThrow { IllegalArgumentException("Missing query parameter: $name") }

    private fun json(body: Any) = ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(body)
}

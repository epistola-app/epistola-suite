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
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.relocation.CatalogResourceMoveBlockedException
import app.epistola.suite.catalog.relocation.CatalogResourceMovePreview
import app.epistola.suite.catalog.relocation.MoveCatalogResource
import app.epistola.suite.catalog.relocation.PreviewCatalogResourceMove
import app.epistola.suite.catalog.relocation.StaleCatalogResourceMovePlanException
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveFeatureToggles
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.queries.GetTenant
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class ResourceGraphHandler {
    fun page(request: ServerRequest): ServerResponse {
        if (!request.resourceGraphEnabled()) return ServerResponse.notFound().build()
        val tenantKey = request.tenantKey()
        return ServerResponse.ok().render(
            "layout/shell",
            mapOf(
                "contentView" to "resource-graph/index",
                "pageTitle" to "Resource graph - Epistola",
                "tenantId" to tenantKey.value,
                "tenant" to GetTenant(tenantKey).query(),
                "activeNavSection" to "resource-graph",
                "resourceRelocationEnabled" to request.resourceRelocationEnabled(),
            ),
        )
    }

    fun nodes(request: ServerRequest): ServerResponse {
        if (!request.resourceGraphEnabled()) return ServerResponse.notFound().build()
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
        val catalogs = ListCatalogs(request.tenantKey()).query().map {
            mapOf("key" to it.id.value, "name" to it.name, "type" to it.type.name.lowercase())
        }
        return json(mapOf("nodes" to nodes, "total" to matches.size, "catalogs" to catalogs))
    }

    fun movePreview(request: ServerRequest): ServerResponse {
        if (!request.resourceGraphEnabled() || !request.resourceRelocationEnabled()) return ServerResponse.notFound().build()
        val source = ResourceAddress(
            resourceType(request.requiredParam("type")),
            request.requiredParam("catalog"),
            request.requiredParam("key"),
        )
        val preview = PreviewCatalogResourceMove(
            request.tenantKey(),
            source,
            app.epistola.suite.catalog.CatalogKey.of(request.requiredParam("targetCatalog")),
        ).query()
        return json(movePreviewDto(preview))
    }

    fun move(request: ServerRequest): ServerResponse {
        if (!request.resourceGraphEnabled() || !request.resourceRelocationEnabled()) return ServerResponse.notFound().build()
        val body = request.body(MoveResourceRequest::class.java)
        val source = ResourceAddress(resourceType(body.type), body.catalog, body.key)
        return try {
            val result = MoveCatalogResource(
                request.tenantKey(),
                source,
                app.epistola.suite.catalog.CatalogKey.of(body.targetCatalog),
                body.planFingerprint,
            ).execute()
            json(movePreviewDto(result))
        } catch (_: StaleCatalogResourceMovePlanException) {
            conflict(mapOf("code" to "stale-plan", "message" to "The move preview is stale; preview it again"))
        } catch (exception: CatalogResourceMoveBlockedException) {
            conflict(mapOf("code" to "move-blocked", "blockers" to exception.blockers))
        }
    }

    fun subgraph(request: ServerRequest): ServerResponse {
        if (!request.resourceGraphEnabled()) return ServerResponse.notFound().build()
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
        if (!request.resourceGraphEnabled()) return ServerResponse.notFound().build()
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
        "resolvedViaAlias" to edge.resolvedViaAlias,
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
    private fun ServerRequest.resourceGraphEnabled() = ResolveFeatureToggles(tenantKey()).query()[KnownFeatures.RESOURCE_GRAPH] == true
    private fun ServerRequest.resourceRelocationEnabled() = ResolveFeatureToggles(tenantKey()).query()[KnownFeatures.RESOURCE_RELOCATION] == true
    private fun ServerRequest.requiredParam(name: String) = param(name).orElseThrow { IllegalArgumentException("Missing query parameter: $name") }

    /** [CatalogResourceMovePreview.resourceId] is the internal surrogate identity and stays server-side. */
    private fun movePreviewDto(preview: CatalogResourceMovePreview) = mapOf(
        "source" to preview.source,
        "target" to preview.target,
        "mutableRewriteCount" to preview.mutableRewriteCount,
        "immutableReferenceCount" to preview.immutableReferenceCount,
        "blockers" to preview.blockers,
        "planFingerprint" to preview.planFingerprint,
        "executable" to preview.executable,
    )

    private fun json(body: Any) = ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(body)
    private fun conflict(body: Any) = ServerResponse.status(409).contentType(MediaType.APPLICATION_JSON).body(body)
}

private data class MoveResourceRequest(
    val type: String,
    val catalog: String,
    val key: String,
    val targetCatalog: String,
    val planFingerprint: String,
)

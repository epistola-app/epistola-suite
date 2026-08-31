// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.graph

enum class CatalogResourceType(val wireName: String) {
    ASSET("asset"),
    CODE_LIST("codeList"),
    FONT("font"),
    ATTRIBUTE("attribute"),
    THEME("theme"),
    STENCIL("stencil"),
    TEMPLATE("template"),
}

data class ResourceAddress(
    val type: CatalogResourceType,
    val catalogKey: String,
    val key: String,
) {
    val id: String get() = "${type.wireName}:$catalogKey:$key"
}

data class ResourceNode(
    /**
     * Stable tenant-local identity, unchanged by a relocation.
     *
     * The address is what authors and catalog exchange use, but it moves. Callers that need to
     * follow a resource across a move -- notably relocation itself -- hold this instead.
     */
    val resourceId: java.util.UUID,
    val address: ResourceAddress,
    val name: String,
    val catalogName: String,
    val catalogType: String,
)

enum class ReferenceSemantics {
    RUNTIME,
    AUTHORING,
    PROVENANCE,
}

enum class ReferenceQualification {
    EXPLICIT,
    RELATIVE,
    TENANT_GLOBAL,
}

enum class ReferenceResolution {
    RESOLVED,
    MISSING,
    AMBIGUOUS,
}

enum class ReferenceLifecycle {
    LIVE,
    HISTORICAL,
}

data class ReferenceSelector(
    val type: CatalogResourceType,
    val catalogKey: String?,
    val key: String,
)

data class ReferenceEvidence(
    val owner: String,
    val lifecycle: ReferenceLifecycle,
    val status: String? = null,
    val version: Int? = null,
    val location: String,
    val pinnedVersion: Int? = null,
)

data class ResourceEdge(
    val id: String,
    val source: ResourceAddress,
    val target: ResourceAddress?,
    val targetSelector: ReferenceSelector,
    val targetCandidates: List<ResourceAddress>,
    val kind: String,
    val semantics: ReferenceSemantics,
    val qualification: ReferenceQualification,
    val resolution: ReferenceResolution,
    val evidence: List<ReferenceEvidence>,
    val resolvedViaAlias: Boolean = false,
) {
    val evidenceCount: Int get() = evidence.size
}

data class TenantResourceGraph(
    val nodes: List<ResourceNode>,
    val edges: List<ResourceEdge>,
)

enum class TraversalDirection {
    INCOMING,
    OUTGOING,
    BOTH,
}

data class ResourceSubgraph(
    val focus: ResourceAddress,
    val nodes: List<ResourceNode>,
    val edges: List<ResourceEdge>,
)

/** Pure breadth-first traversal shared by the UI query and unit tests. */
fun TenantResourceGraph.traverse(
    focus: ResourceAddress,
    direction: TraversalDirection,
    depth: Int,
): ResourceSubgraph {
    require(depth in 1..3) { "Graph depth must be between 1 and 3" }

    val visited = linkedSetOf(focus)
    var frontier = setOf(focus)
    repeat(depth) {
        val next = linkedSetOf<ResourceAddress>()
        for (edge in edges) {
            val target = edge.target ?: continue
            if (direction != TraversalDirection.INCOMING && edge.source in frontier) next += target
            if (direction != TraversalDirection.OUTGOING && target in frontier) next += edge.source
        }
        next.removeAll(visited)
        visited += next
        frontier = next
    }

    val selectedEdges = edges.filter { edge ->
        edge.source in visited && (edge.target == null || edge.target in visited)
    }
    return ResourceSubgraph(
        focus = focus,
        nodes = nodes.filter { it.address in visited },
        edges = selectedEdges,
    )
}

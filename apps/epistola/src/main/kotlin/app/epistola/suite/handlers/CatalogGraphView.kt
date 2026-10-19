// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.queries.CatalogDependencyGraph
import app.epistola.suite.catalog.queries.CatalogGraphEdge

/**
 * The catalog dependency graph laid out for drawing as SVG on the server (the CSP allows no graph
 * library, and none is needed): columns left to right, a catalog one column right of the furthest
 * catalog it depends on, so what everything builds on stands at the left. Arrows run from a catalog
 * to the catalogs it uses.
 */
data class CatalogGraphView(val width: Int, val height: Int, val nodes: List<Box>, val edges: List<Arrow>) {
    val isEmpty: Boolean get() = nodes.isEmpty()

    /** A catalog. [href] null for one the tenant does not have, which is drawn dashed. */
    data class Box(
        val key: String,
        val label: String,
        val detail: String,
        val x: Int,
        val y: Int,
        val href: String?,
        val focus: Boolean,
        val missing: Boolean,
    ) {
        val width: Int get() = BOX_WIDTH
        val height: Int get() = BOX_HEIGHT
        val labelX: Int get() = x + 12
    }

    /** One dependency: a curve from the dependent's left edge to the dependency's right edge. */
    data class Arrow(val path: String, val label: String, val labelX: Int, val labelY: Int, val untied: Boolean)

    companion object {
        const val BOX_WIDTH = 210
        const val BOX_HEIGHT = 52
        private const val COLUMN_GAP = 130
        private const val ROW_GAP = 22
        private const val MARGIN = 16

        /**
         * Lays out [graph]. With [focus], only that catalog, what it depends on and what depends on it,
         * each followed transitively. [hrefOf] links a catalog the tenant has.
         */
        fun of(graph: CatalogDependencyGraph, focus: String? = null, hrefOf: (String) -> String): CatalogGraphView {
            val missingKey = { edge: CatalogGraphEdge -> edge.to ?: "missing:${edge.toIdentity}" }
            var edges = graph.edges.filter { it.from != missingKey(it) }
            val names = graph.nodes.associate { it.catalogKey to it }
            if (focus != null) {
                val uses = reach(focus, edges.groupBy({ it.from }, { missingKey(it) }))
                val usedBy = reach(focus, edges.groupBy({ missingKey(it) }, { it.from }))
                val keep = uses + usedBy + focus
                edges = edges.filter { (it.from in uses + focus && missingKey(it) in uses) || (it.from in usedBy && missingKey(it) in usedBy + focus) }
                return place(names.keys.filter { it in keep } + edges.map(missingKey).filter { it !in names }.distinct(), edges, missingKey, names, focus, hrefOf)
            }
            return place(names.keys.toList() + edges.map(missingKey).filter { it !in names }.distinct(), edges, missingKey, names, null, hrefOf)
        }

        /** Every key reachable from [start] along [next], [start] excluded. */
        private fun reach(start: String, next: Map<String, List<String>>): Set<String> {
            val seen = mutableSetOf<String>()
            val queue = ArrayDeque(next[start].orEmpty())
            while (queue.isNotEmpty()) {
                val key = queue.removeFirst()
                if (key != start && seen.add(key)) queue.addAll(next[key].orEmpty())
            }
            return seen
        }

        private fun place(
            keys: List<String>,
            edges: List<CatalogGraphEdge>,
            target: (CatalogGraphEdge) -> String,
            names: Map<String, app.epistola.suite.catalog.queries.CatalogGraphNode>,
            focus: String?,
            hrefOf: (String) -> String,
        ): CatalogGraphView {
            if (keys.isEmpty()) return CatalogGraphView(0, 0, emptyList(), emptyList())
            val uses = edges.groupBy({ it.from }, target)
            // A catalog's column: one past the furthest catalog it uses. Bounded, so a cycle the
            // declarations should never contain cannot loop for ever.
            val column = keys.associateWith { 0 }.toMutableMap()
            repeat(keys.size) {
                keys.forEach { key -> column[key] = (uses[key].orEmpty().maxOfOrNull { (column[it] ?: 0) + 1 } ?: 0).coerceAtMost(keys.size) }
            }
            val label = { key: String -> names[key]?.name ?: key.removePrefix("missing:").substringAfterLast('/') }
            val byColumn = keys.groupBy { column.getValue(it) }.mapValues { (_, members) -> members.sortedBy { label(it).lowercase() } }
            val boxes = byColumn.flatMap { (col, members) ->
                members.mapIndexed { row, key ->
                    val node = names[key]
                    Box(
                        key = key,
                        label = label(key),
                        detail = when {
                            node == null -> "Not in this tenant: ${key.removePrefix("missing:")}"
                            node.type == CatalogType.SUBSCRIBED -> "Subscribed · ${node.catalogKey}"
                            else -> node.catalogKey
                        },
                        x = MARGIN + col * (BOX_WIDTH + COLUMN_GAP),
                        y = MARGIN + row * (BOX_HEIGHT + ROW_GAP),
                        href = node?.let { hrefOf(it.catalogKey) },
                        focus = key == focus,
                        missing = node == null,
                    )
                }
            }
            val at = boxes.associateBy { it.key }
            val arrows = edges.mapNotNull { edge ->
                val from = at[edge.from] ?: return@mapNotNull null
                val to = at[target(edge)] ?: return@mapNotNull null
                val x1 = from.x
                val y1 = from.y + BOX_HEIGHT / 2
                val x2 = to.x + BOX_WIDTH
                val y2 = to.y + BOX_HEIGHT / 2
                val bend = ((x1 - x2) / 2).coerceAtLeast(40)
                Arrow(
                    path = "M $x1 $y1 C ${x1 - bend} $y1, ${x2 + bend} $y2, $x2 $y2",
                    label = edge.release?.let { "v$it" } ?: "not tied",
                    labelX = (x1 + x2) / 2,
                    labelY = (y1 + y2) / 2 - 6,
                    untied = edge.release == null,
                )
            }
            val width = (boxes.maxOf { it.x } + BOX_WIDTH + MARGIN)
            val height = (boxes.maxOf { it.y } + BOX_HEIGHT + MARGIN)
            return CatalogGraphView(width, height, boxes, arrows)
        }
    }
}

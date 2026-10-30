// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.graph.TenantResourceGraph

/**
 * Whether relocating a resource would leave the tenant's catalogs mutually dependent.
 *
 * A move is not only a storage operation: carrying a resource across a catalog boundary rewrites
 * the dependency edges between those catalogs, and can close a loop. That matters because catalog
 * ordering is load-bearing elsewhere — a release pins the release of every catalog it depends on,
 * so those are released first, and a cycle leaves no order to release them in.
 *
 * Left unchecked, a move could therefore make a tenant's catalogs unreleasable, and the failure
 * would surface later, to someone releasing, with nothing connecting it back to the move.
 * ADR 0014 calls for blocking it for exactly that reason.
 */
object CatalogDependencyCycles {
    /**
     * Returns the catalogs forming a cycle once every relocation in [relocations] has been applied,
     * or null when the result is still acyclic.
     *
     * Edges are derived from resolved graph edges whose endpoints sit in different catalogs, with
     * the moving resource counted at its destination. Self-edges within one catalog are not
     * dependencies; unresolved edges have no target catalog and are ignored.
     */
    fun introducedBy(
        graph: TenantResourceGraph,
        relocations: List<ResourceRelocation>,
    ): List<String>? {
        // Evaluated for the batch as a whole, not member by member. Moving several resources
        // together is how an author resolves a loop that any single move would be blocked on, so
        // judging them individually would refuse exactly the batches that fix the problem.
        val destinations = relocations.associate { it.source to it.target.catalogAddress }
        val edges = mutableSetOf<Pair<String, String>>()
        for (edge in graph.edges) {
            val target = edge.target ?: continue
            val from = destinations[edge.source] ?: edge.source.catalogAddress
            val to = destinations[target] ?: target.catalogAddress
            if (from != to) edges += from to to
        }
        return findCycle(edges)
    }

    /**
     * Kahn's algorithm, keeping whatever cannot be ordered. Deterministic so a blocker message does
     * not change between identical previews.
     */
    private fun findCycle(edges: Set<Pair<String, String>>): List<String>? {
        val nodes = edges.flatMap { listOf(it.first, it.second) }.toMutableSet()
        val unmet = nodes.associateWith { node -> edges.filter { it.first == node }.map { it.second }.toMutableSet() }
            .toMutableMap()

        while (true) {
            val next = unmet.entries.filter { it.value.isEmpty() }.minByOrNull { it.key }?.key ?: break
            unmet.remove(next)
            unmet.values.forEach { it.remove(next) }
        }
        return unmet.keys.sorted().takeIf { it.isNotEmpty() }
    }
}

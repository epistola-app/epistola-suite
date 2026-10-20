// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration.preflight

/**
 * Catalogs that use each other: a dependency cycle, which 2.0 does not allow. Each would have to pin
 * the other's first release, and neither can be released first, so neither ever renders.
 */
object CatalogDependencyCycleCheck : UpgradePreflightCheck {
    override val id = "1x-catalog-dependency-cycles"
    override val upgrade = OneXCatalogReferences.UPGRADE

    override fun appliesTo(context: UpgradePreflightContext) = OneXCatalogReferences.isOneX(context)

    override fun run(context: UpgradePreflightContext): List<UpgradePreflightProblem> {
        val references = OneXCatalogReferences.of(context)
        return references.uses
            .filter { it.usedCatalog in references.catalogs.authored[it.tenantKey].orEmpty() }
            .groupBy { it.tenantKey }
            .toSortedMap()
            .flatMap { (tenant, uses) ->
                cyclesOf(uses.map { it.catalogKey to it.usedCatalog }.toSet()).map { members ->
                    UpgradePreflightProblem(
                        tenantKey = tenant,
                        summary = "catalogs ${members.joinToString(", ") { "'$it'" }} use each other, which 2.0 does not allow.",
                        fix = "Move what they share into one of them, or into a catalog they all use. They use:",
                        details = uses.filter { it.catalogKey in members && it.usedCatalog in members }.map { it.describe() }.sorted(),
                    )
                }
            }
    }

    /** The groups of catalogs that use each other: strongly connected components of two or more, sorted. */
    internal fun cyclesOf(edges: Set<Pair<String, String>>): List<List<String>> {
        val next = edges.groupBy({ it.first }, { it.second })
        val index = mutableMapOf<String, Int>()
        val low = mutableMapOf<String, Int>()
        val stack = ArrayDeque<String>()
        val onStack = mutableSetOf<String>()
        val components = mutableListOf<List<String>>()
        var counter = 0
        fun visit(node: String) {
            index[node] = counter
            low[node] = counter
            counter++
            stack.addLast(node)
            onStack += node
            for (target in next[node].orEmpty().sorted()) {
                if (target !in index) {
                    visit(target)
                    low[node] = minOf(low.getValue(node), low.getValue(target))
                } else if (target in onStack) {
                    low[node] = minOf(low.getValue(node), index.getValue(target))
                }
            }
            if (low[node] == index[node]) {
                val component = mutableListOf<String>()
                do {
                    val member = stack.removeLast()
                    onStack -= member
                    component += member
                } while (member != node)
                if (component.size > 1) components += component.sorted()
            }
        }
        (edges.map { it.first } + edges.map { it.second }).toSortedSet().forEach { if (it !in index) visit(it) }
        return components.sortedBy { it.first() }
    }
}

/**
 * A catalog that uses a catalog the tenant does not have: no dependency can name it, so the catalog
 * using it is never released.
 */
object MissingCatalogCheck : UpgradePreflightCheck {
    override val id = "1x-missing-catalogs"
    override val upgrade = OneXCatalogReferences.UPGRADE

    override fun appliesTo(context: UpgradePreflightContext) = OneXCatalogReferences.isOneX(context)

    override fun run(context: UpgradePreflightContext): List<UpgradePreflightProblem> {
        val references = OneXCatalogReferences.of(context)
        return references.uses
            .filter { it.usedCatalog !in references.catalogs.known[it.tenantKey].orEmpty() }
            .groupBy { Triple(it.tenantKey, it.catalogKey, it.usedCatalog) }
            .toSortedMap(compareBy({ it.first }, { it.second }, { it.third }))
            .map { (key, uses) ->
                UpgradePreflightProblem(
                    tenantKey = key.first,
                    summary = "catalog '${key.second}' uses catalog '${key.third}', which this tenant does not have.",
                    fix = "Install it, or change what uses it:",
                    details = uses.map { it.describe() }.sorted(),
                )
            }
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.queries.CatalogDependencyGraph
import app.epistola.suite.catalog.queries.CatalogEditabilities
import app.epistola.suite.catalog.queries.CatalogEditability
import app.epistola.suite.catalog.queries.CatalogGraphEdge
import app.epistola.suite.catalog.queries.CatalogGraphNode
import app.epistola.suite.catalog.queries.CatalogReadOnlyReason
import app.epistola.suite.common.ids.CatalogKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Laying out the catalog dependency graph: what everything builds on stands left, and a focus narrows it. */
class CatalogGraphViewTest {

    private fun node(key: String) = CatalogGraphNode(key, key.replaceFirstChar { it.uppercase() }, CatalogType.AUTHORED, "r/n/$key")
    private fun edge(from: String, to: String?, release: String? = "1.0.0") = CatalogGraphEdge(from, to, "r/n/${to ?: "elsewhere"}", release)

    // base <- shared <- letters ; reports -> shared ; unrelated stands alone ; letters -> elsewhere (not in the tenant)
    private val graph = CatalogDependencyGraph(
        nodes = listOf(node("base"), node("shared"), node("letters"), node("reports"), node("unrelated")),
        edges = listOf(edge("shared", "base"), edge("letters", "shared"), edge("reports", "shared", release = null), edge("letters", null)),
    )

    private fun view(focus: String? = null) = CatalogGraphView.of(graph, focus) { "/c/$it" }

    @Test
    fun `a catalog stands one column right of the furthest catalog it uses`() {
        val x = view().nodes.associate { it.key to it.x }
        assertThat(x.getValue("base")).isEqualTo(x.getValue("unrelated"))
        assertThat(x.getValue("shared")).isGreaterThan(x.getValue("base"))
        assertThat(x.getValue("letters")).isGreaterThan(x.getValue("shared")).isEqualTo(x.getValue("reports"))
    }

    @Test
    fun `a catalog the tenant does not have is drawn without a link, and an untied dependency says so`() {
        val view = view()
        val missing = view.nodes.single { it.missing }
        assertThat(missing.href).isNull()
        assertThat(missing.detail).contains("Not in this tenant")
        assertThat(view.nodes.single { it.key == "letters" }.href).isEqualTo("/c/letters")
        assertThat(view.edges.single { it.untied }.label).contains("not tied")
        assertThat(view.edges).hasSize(4)
    }

    @Test
    fun `a focus keeps what the catalog uses and what uses it, transitively, and nothing else`() {
        val focused = view(focus = "shared")
        assertThat(focused.nodes.map { it.key }).containsExactlyInAnyOrder("base", "shared", "letters", "reports")
        assertThat(focused.nodes.single { it.focus }.key).isEqualTo("shared")
        assertThat(focused.edges).`as`("letters' other dependency is not about shared").hasSize(3)

        assertThat(view(focus = "unrelated").nodes.map { it.key }).containsExactly("unrelated")
        assertThat(view(focus = "unrelated").edges).isEmpty()
    }

    @Test
    fun `a catalog that cannot be changed here says why under its name, whatever its type`() {
        // `shared` is typed SUBSCRIBED, as a received catalog is; it can be changed here, so nothing marks it.
        val received = graph.copy(nodes = graph.nodes.map { if (it.catalogKey == "shared") it.copy(type = CatalogType.SUBSCRIBED) else it })
        val editability = CatalogEditabilities(
            received.nodes.associate { node ->
                CatalogKey.of(node.catalogKey) to CatalogEditability(CatalogReadOnlyReason.SUPPLIED_BY_EPISTOLA.takeIf { node.catalogKey == "base" })
            },
        )
        val details = CatalogGraphView.of(received, readOnlyLabel = CatalogGraphView.readOnlyLabels(editability)) { "/c/$it" }.nodes.associate { it.key to it.detail }
        assertThat(details.getValue("base")).isEqualTo("Supplied by Epistola · base")
        assertThat(details.getValue("shared")).`as`("received, and can be changed here").isEqualTo("shared")
    }
}

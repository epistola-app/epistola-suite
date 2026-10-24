// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

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

    // Every catalog of the tenant in namespace `n`, so each is drawn and linked by its address, `n/key`.
    private fun node(key: String) = CatalogGraphNode(key, key.replaceFirstChar { it.uppercase() }, "r/n/$key", "n")
    private fun edge(from: String, to: String?, release: String? = "1.0.0") = CatalogGraphEdge(from, to, "r/n/${to ?: "elsewhere"}", release, fromNamespace = "n", toNamespace = to?.let { "n" })

    // base <- shared <- letters ; reports -> shared ; unrelated stands alone ; letters -> elsewhere (not in the tenant)
    private val graph = CatalogDependencyGraph(
        nodes = listOf(node("base"), node("shared"), node("letters"), node("reports"), node("unrelated")),
        edges = listOf(edge("shared", "base"), edge("letters", "shared"), edge("reports", "shared", release = null), edge("letters", null)),
    )

    private fun view(focus: String? = null) = CatalogGraphView.of(graph, focus) { "/c/$it" }

    private fun Map<String, Int>.at(key: String) = getValue("n/$key")

    @Test
    fun `a catalog stands one column right of the furthest catalog it uses`() {
        val x = view().nodes.associate { it.key to it.x }
        assertThat(x.at("base")).isEqualTo(x.at("unrelated"))
        assertThat(x.at("shared")).isGreaterThan(x.at("base"))
        assertThat(x.at("letters")).isGreaterThan(x.at("shared")).isEqualTo(x.at("reports"))
    }

    @Test
    fun `a catalog the tenant does not have is drawn without a link, and an untied dependency says so`() {
        val view = view()
        val missing = view.nodes.single { it.missing }
        assertThat(missing.href).isNull()
        assertThat(missing.detail).contains("Not in this tenant")
        assertThat(view.nodes.single { it.key == "n/letters" }.href).isEqualTo("/c/n/letters")
        assertThat(view.edges.single { it.untied }.label).contains("not tied")
        assertThat(view.edges).hasSize(4)
    }

    @Test
    fun `a focus keeps what the catalog uses and what uses it, transitively, and nothing else`() {
        val focused = view(focus = "n/shared")
        assertThat(focused.nodes.map { it.key }).containsExactlyInAnyOrder("n/base", "n/shared", "n/letters", "n/reports")
        assertThat(focused.nodes.single { it.focus }.key).isEqualTo("n/shared")
        assertThat(view(focus = "shared").nodes.single { it.focus }.key).`as`("a key one catalog has").isEqualTo("n/shared")
        assertThat(focused.edges).`as`("letters' other dependency is not about shared").hasSize(3)

        assertThat(view(focus = "unrelated").nodes.map { it.key }).containsExactly("n/unrelated")
        assertThat(view(focus = "unrelated").edges).isEmpty()
    }

    @Test
    fun `a catalog that cannot be changed here says why under its name`() {
        // `shared` can be changed here, so nothing marks it; `base` is supplied by Epistola.
        val received = graph
        val editability = CatalogEditabilities(
            received.nodes.associate { node ->
                app.epistola.suite.common.ids.CatalogId(node.namespace, CatalogKey.of(node.catalogKey), app.epistola.suite.common.ids.TenantId(app.epistola.suite.common.ids.TenantKey.of("graph"))) to CatalogEditability(CatalogReadOnlyReason.SUPPLIED_BY_EPISTOLA.takeIf { node.catalogKey == "base" })
            },
        )
        val details = CatalogGraphView.of(received, readOnlyLabel = CatalogGraphView.readOnlyLabels(editability)) { "/c/$it" }.nodes.associate { it.key to it.detail }
        assertThat(details.getValue("n/base")).isEqualTo("Supplied by Epistola · n/base")
        assertThat(details.getValue("n/shared")).`as`("received, and can be changed here").isEqualTo("n/shared")
    }

    @Test
    fun `two catalogs sharing a key are two boxes, each drawn and linked by its address`() {
        val twoLetters = CatalogDependencyGraph(
            nodes = listOf(
                CatalogGraphNode("letters", "Acme letters", "r/acme/letters", "acme"),
                CatalogGraphNode("letters", "Bureau letters", "r/bureau/letters", "bureau"),
                CatalogGraphNode("base", "Base", "r/acme/base", "acme"),
            ),
            edges = listOf(CatalogGraphEdge("letters", "base", "r/acme/base", "1.0.0", fromNamespace = "bureau", toNamespace = "acme")),
        )
        val view = CatalogGraphView.of(twoLetters) { "/c/$it" }
        assertThat(view.nodes.map { it.href }).containsExactlyInAnyOrder("/c/acme/letters", "/c/bureau/letters", "/c/acme/base")
        assertThat(view.edges).hasSize(1)
        // Focused on one, the other is not drawn: it uses nothing and nothing uses it.
        assertThat(CatalogGraphView.of(twoLetters, focus = "bureau/letters") { "/c/$it" }.nodes.map { it.key }).containsExactlyInAnyOrder("bureau/letters", "acme/base")
        // Every catalog by its address, whether or not another has its key (ADR 0034, section 9).
        assertThat(CatalogGraphView.of(twoLetters, focus = "acme/base") { "/c/$it" }.nodes.single { it.focus }.key).isEqualTo("acme/base")
    }
}

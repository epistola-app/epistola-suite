// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.graph

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class ResourceGraphTest {
    private val template = ResourceAddress(CatalogResourceType.TEMPLATE, "letters", "welcome")
    private val theme = ResourceAddress(CatalogResourceType.THEME, "brand", "corporate")
    private val font = ResourceAddress(CatalogResourceType.FONT, "brand", "body")
    private val asset = ResourceAddress(CatalogResourceType.ASSET, "brand", "01900000-0000-7000-8000-000000000001")

    private val graph = TenantResourceGraph(
        nodes = listOf(template, theme, font, asset).map {
            ResourceNode(it, it.key, it.catalogKey, "AUTHORED")
        },
        edges = listOf(
            edge("template-theme", template, theme),
            edge("theme-font", theme, font),
            edge("font-asset", font, asset),
        ),
    )

    @Test
    fun `outgoing traversal observes depth`() {
        val result = graph.traverse(template, TraversalDirection.OUTGOING, 2)

        assertThat(result.nodes.map { it.address }).containsExactly(template, theme, font)
        assertThat(result.edges.map { it.id }).containsExactly("template-theme", "theme-font")
    }

    @Test
    fun `incoming traversal follows references back to their users`() {
        val result = graph.traverse(font, TraversalDirection.INCOMING, 2)

        assertThat(result.nodes.map { it.address }).containsExactly(template, theme, font)
    }

    @Test
    fun `both traversal expands in both directions`() {
        val result = graph.traverse(theme, TraversalDirection.BOTH, 1)

        assertThat(result.nodes.map { it.address }).containsExactly(template, theme, font)
    }

    @Test
    fun `traversal rejects unbounded depth`() {
        assertThatIllegalArgumentException().isThrownBy {
            graph.traverse(template, TraversalDirection.BOTH, 4)
        }
    }

    private fun edge(id: String, source: ResourceAddress, target: ResourceAddress) = ResourceEdge(
        id = id,
        source = source,
        target = target,
        targetSelector = ReferenceSelector(target.type, target.catalogKey, target.key),
        targetCandidates = listOf(target),
        kind = "uses",
        semantics = ReferenceSemantics.RUNTIME,
        qualification = ReferenceQualification.EXPLICIT,
        resolution = ReferenceResolution.RESOLVED,
        evidence = listOf(ReferenceEvidence("test", ReferenceLifecycle.LIVE, location = "test")),
    )
}

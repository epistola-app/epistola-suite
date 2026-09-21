// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ReferenceQualification
import app.epistola.suite.catalog.graph.ReferenceResolution
import app.epistola.suite.catalog.graph.ReferenceSelector
import app.epistola.suite.catalog.graph.ReferenceSemantics
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.graph.ResourceEdge
import app.epistola.suite.catalog.graph.ResourceNode
import app.epistola.suite.catalog.graph.TenantResourceGraph
import app.epistola.suite.common.ids.ResourceIdentity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class CatalogDependencyCyclesTest {
    private fun address(catalog: String, key: String, type: CatalogResourceType = CatalogResourceType.STENCIL) = ResourceAddress(type, catalog, key)

    private fun graphOf(vararg edges: Pair<ResourceAddress, ResourceAddress>): TenantResourceGraph = graphOfKinds(*edges.map { Triple(it.first, it.second, "test") }.toTypedArray())

    private fun graphOfKinds(vararg edges: Triple<ResourceAddress, ResourceAddress, String>): TenantResourceGraph {
        val addresses = edges.flatMap { listOf(it.first, it.second) }.distinct()
        return TenantResourceGraph(
            nodes = addresses.map {
                ResourceNode(ResourceIdentity.of(UUID.nameUUIDFromBytes(it.id.toByteArray())), it, it.key, it.catalogKey, "AUTHORED")
            },
            edges = edges.mapIndexed { index, (from, to, kind) ->
                ResourceEdge(
                    id = "e$index",
                    source = from,
                    target = to,
                    targetSelector = ReferenceSelector(to.type, to.catalogKey, to.key),
                    targetCandidates = listOf(to),
                    kind = kind,
                    semantics = ReferenceSemantics.RUNTIME,
                    qualification = ReferenceQualification.EXPLICIT,
                    resolution = ReferenceResolution.RESOLVED,
                    evidence = emptyList(),
                )
            },
        )
    }

    /**
     * A font's face binaries travel inside the font when exported, so the catalog storing a face's
     * binary is not something a restore has to install first.
     */
    @Test
    fun `where a font face's binary is stored is not a catalog dependency`() {
        val theme = address("letters", "brand", CatalogResourceType.THEME)
        val font = address("letters", "acme", CatalogResourceType.FONT)
        val graph = graphOfKinds(
            Triple(theme, font, "theme-font"),
            Triple(font, address("letters", "acme-regular", CatalogResourceType.IMAGE), "font-face-asset"),
        )

        assertThat(CatalogDependencyCycles.introducedBy(graph, listOf(font.movedTo(CatalogKey.of("shared"))))).isNull()
    }

    @Test
    fun `a move that leaves catalogs independent is allowed`() {
        // letters/header uses shared/base. Moving header to shared removes the cross-catalog edge.
        val header = address("letters", "header")
        val graph = graphOf(header to address("shared", "base"))

        assertThat(CatalogDependencyCycles.introducedBy(graph, listOf(header.movedTo(CatalogKey.of("shared"))))).isNull()
    }

    @Test
    fun `a move that closes a loop between two catalogs is detected`() {
        // shared/base already uses letters/logo, so moving letters/header into shared is fine --
        // but moving shared/base's dependency the other way would make them mutual.
        val header = address("letters", "header")
        val graph = graphOf(
            header to address("letters", "logo"),
            address("shared", "base") to address("letters", "logo"),
            address("letters", "cover") to address("shared", "base"),
        )

        // Moving header to shared makes shared depend on letters, while letters/cover already
        // depends on shared.
        assertThat(CatalogDependencyCycles.introducedBy(graph, listOf(header.movedTo(CatalogKey.of("shared")))))
            .containsExactly("letters", "shared")
    }

    @Test
    fun `an unresolved edge has no target catalog and cannot form a cycle`() {
        val header = address("letters", "header")
        val graph = TenantResourceGraph(
            nodes = listOf(ResourceNode(ResourceIdentity.generate(), header, "Header", "letters", "AUTHORED")),
            edges = listOf(
                ResourceEdge(
                    id = "dangling",
                    source = header,
                    target = null,
                    targetSelector = ReferenceSelector(CatalogResourceType.THEME, "gone", "missing"),
                    targetCandidates = emptyList<ResourceAddress>(),
                    kind = "test",
                    semantics = ReferenceSemantics.RUNTIME,
                    qualification = ReferenceQualification.EXPLICIT,
                    resolution = ReferenceResolution.MISSING,
                    evidence = emptyList(),
                ),
            ),
        )

        assertThat(CatalogDependencyCycles.introducedBy(graph, listOf(header.movedTo(CatalogKey.of("shared"))))).isNull()
    }
}

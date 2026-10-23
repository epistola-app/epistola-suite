// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.releases.BehindBranch
import app.epistola.suite.catalog.releases.BehindLine
import app.epistola.suite.catalog.releases.BehindRelease
import app.epistola.suite.catalog.releases.JoinedRelease
import app.epistola.suite.catalog.releases.ReleasedChange
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The releases a draft lacks in words, and the drawing of its line laid out ([CatalogBehindView], [BehindDiagram]). */
class CatalogBehindViewTest {

    private val released = setOf("1.0.0", "1.0.1", "1.1.0", "1.1.1", "1.2.0", "1.2.1")

    private fun view(behind: List<BehindRelease>, line: BehindLine? = null, isNext: Boolean = true) = CatalogBehindView.of(TenantKey.of("acme"), CatalogKey.of("letters"), if (isNext) "Next release" else "Redesign", isNext, behind, line, released)

    private fun changed(vararg names: String) = names.map { ReleasedChange("template", it.lowercase(), "modified", it) }

    /** The demo's line: 1.0.0 → 1.1.0 → 1.2.0 → 1.2.1, lacking 1.0.1 (made on 1.0.0) and 1.1.1 (made on 1.1.0). */
    private val demo = BehindLine(
        line = listOf("1.0.0", "1.1.0", "1.2.0", "1.2.1"),
        joined = emptyList(),
        behind = listOf(BehindBranch("1.0.1", "1.0.0"), BehindBranch("1.1.1", "1.1.0")),
    )

    private val demoBehind = listOf(BehindRelease("1.0.1", changed("Template A"), "1.0.0"), BehindRelease("1.1.1", changed("Template B"), "1.1.0"))

    @Test
    fun `one release is a sentence with what it was made on, wherever that was`() {
        assertThat(view(listOf(BehindRelease("1.0.1", changed("Invoice"), "1.0.0"))).single!!.summary)
            .isEqualTo("was made on v1.0.0 and is not in the next release: it changed Invoice.")
        assertThat(view(listOf(BehindRelease("1.0.5", emptyList(), "0.9.0")), isNext = false).single!!.summary)
            .isEqualTo("was received from elsewhere, made on v0.9.0, and is not in this draft.")
        assertThat(view(listOf(BehindRelease("1.0.0", emptyList(), null))).single!!.madeOn).isEqualTo("started from nothing")
        assertThat(view(listOf(BehindRelease("1.0.1", emptyList(), "1.0.0"))).diagram).`as`("one is said, not drawn").isNull()
    }

    @Test
    fun `several are titled with their count, each row linked to its release page`() {
        val view = view(demoBehind, demo)

        assertThat(view.several).isTrue()
        assertThat(view.title).isEqualTo("2 releases are not in the next release yet")
        assertThat(view(demoBehind, demo, isNext = false).title).isEqualTo("2 releases are not in this draft yet")
        assertThat(view.rows.map { it.href }).containsExactly("/tenants/acme/catalogs/letters/releases/1.0.1", "/tenants/acme/catalogs/letters/releases/1.1.1")
        assertThat(view.rows.map { it.madeOn }).containsExactly("made on v1.0.0", "made on v1.1.0")
    }

    @Test
    fun `what a release changed is said by name and kind of change, with a long list cut`() {
        val changes = changed("A", "B", "C", "D", "E", "F", "G") +
            ReleasedChange("theme", "footer", "added", "Footer") + ReleasedChange("theme", "old", "removed", null)

        assertThat(CatalogBehindView.changedWords(changes)).isEqualTo("changed A, B, C, D, E and 2 more, added Footer, and removed old")
        assertThat(CatalogBehindView.changedWords(emptyList())).isNull()
    }

    @Test
    fun `the drawing puts the line in a row ending in the draft, and each missing release below, right of the one it was made on`() {
        val diagram = view(demoBehind, demo).diagram!!
        val byVersion = diagram.nodes.filter { it.version != null }.associateBy { it.version }
        val line = diagram.nodes.filter { it.kind == BehindDiagram.Kind.LINE || it.kind == BehindDiagram.Kind.DRAFT }

        assertThat(line.map { it.label }).containsExactly("v1.0.0", "v1.1.0", "v1.2.0", "v1.2.1", "Next release")
        assertThat(line.map { it.y }.distinct()).hasSize(1)
        assertThat(line.zipWithNext().all { (a, b) -> a.x + a.width < b.x }).isTrue()
        listOf("1.0.1" to "1.0.0", "1.1.1" to "1.1.0").forEach { (missing, madeOn) ->
            val node = byVersion.getValue(missing)
            assertThat(node.kind).isEqualTo(BehindDiagram.Kind.BEHIND)
            assertThat(node.caption).isEqualTo("not in it")
            assertThat(node.y).`as`("below the line").isGreaterThan(line.first().y)
            assertThat(node.x).`as`("right of the release it was made on").isGreaterThan(byVersion.getValue(madeOn).centerX)
        }
        assertThat(diagram.edges.count { it.toBehind }).isEqualTo(2)
        assertThat(diagram.edges.count { !it.toBehind }).`as`("the line's four links").isEqualTo(4)
        assertNoOverlap(diagram)
        assertThat(diagram.width).isEqualTo(diagram.nodes.maxOf { it.x + it.width } + 12)
        assertThat(diagram.description).isEqualTo(
            "Next release continues from v1.0.0, v1.1.0, v1.2.0 and v1.2.1. Not in it: v1.0.1, made on v1.0.0; v1.1.1, made on v1.1.0.",
        )
    }

    @Test
    fun `a release merged in is drawn above, joined to the release that took it in, and one made on it hangs off it`() {
        val line = BehindLine(
            line = listOf("1.1.0", "1.2.0", "1.2.1"),
            joined = listOf(JoinedRelease("1.0.1", into = "1.2.0")),
            behind = listOf(BehindBranch("1.0.2", "1.0.1"), BehindBranch("1.1.1", "1.1.0")),
        )
        val diagram = view(listOf(BehindRelease("1.0.2", emptyList(), "1.0.1"), BehindRelease("1.1.1", emptyList(), "1.1.0")), line).diagram!!
        val byVersion = diagram.nodes.filter { it.version != null }.associateBy { it.version }

        val joined = byVersion.getValue("1.0.1")
        assertThat(joined.kind).isEqualTo(BehindDiagram.Kind.JOINED)
        assertThat(joined.caption).isEqualTo("merged in")
        assertThat(joined.y).`as`("above the line").isLessThan(byVersion.getValue("1.2.0").y)
        assertThat(joined.x + joined.width).`as`("left of where it was taken in").isLessThan(byVersion.getValue("1.2.0").centerX)
        assertThat(byVersion.getValue("1.0.2").y).`as`("off the merged one, further up").isLessThan(joined.y)
        assertThat(byVersion.getValue("1.1.1").y).isGreaterThan(byVersion.getValue("1.1.0").y)
        assertThat(diagram.description).contains("Merged into it: v1.0.1.")
        assertNoOverlap(diagram)
    }

    @Test
    fun `a missing release made on nothing here is drawn dashed and unconnected, and a long stretch with nothing off it folds`() {
        val line = BehindLine(
            line = listOf("1.0.0", "1.1.0", "1.2.0", "1.3.0", "1.4.0", "1.5.0"),
            joined = emptyList(),
            behind = listOf(BehindBranch("1.0.1", "1.0.0"), BehindBranch("1.2.5", null)),
        )
        val diagram = view(listOf(BehindRelease("1.0.1", emptyList(), "1.0.0"), BehindRelease("1.2.5", emptyList(), "0.9.0")), line).diagram!!

        assertThat(diagram.nodes.filter { it.y == diagram.nodes.first().y }.map { it.label })
            .`as`("1.1.0 to 1.4.0 have nothing off them")
            .containsExactly("v1.0.0", "4 more", "v1.5.0", "Next release")
        val elsewhere = diagram.nodes.single { it.version == "1.2.5" }
        assertThat(elsewhere.unattached).isTrue()
        assertThat(diagram.edges.count { it.toBehind }).`as`("only 1.0.1 is connected").isEqualTo(1)
        assertThat(diagram.description).contains("v1.2.5, received from elsewhere, made on v0.9.0")
        assertNoOverlap(diagram)
    }

    /** No two boxes in one row touch. */
    private fun assertNoOverlap(diagram: BehindDiagram) {
        diagram.nodes.groupBy { it.y }.values.forEach { row ->
            row.sortedBy { it.x }.zipWithNext().forEach { (a, b) -> assertThat(a.x + a.width).isLessThan(b.x) }
        }
    }
}

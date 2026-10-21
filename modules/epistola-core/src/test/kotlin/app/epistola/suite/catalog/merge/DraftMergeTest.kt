// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.merge

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DraftMergeTest {

    private fun outcomes(base: Map<String, String>, ours: Map<String, String>, theirs: Map<String, String>) = DraftMerge.classify(base, ours, theirs).associate { it.contentKey to it.outcome }

    @Test
    fun `each side's change is kept or taken, and only a change on both sides conflicts`() {
        val base = mapOf("theme/a" to "1", "theme/b" to "1", "theme/c" to "1", "theme/d" to "1", "theme/e" to "1")
        val ours = mapOf("theme/a" to "1", "theme/b" to "2", "theme/c" to "1", "theme/d" to "3", "theme/e" to "4")
        val theirs = mapOf("theme/a" to "1", "theme/b" to "1", "theme/c" to "5", "theme/d" to "6", "theme/e" to "4")

        assertThat(outcomes(base, ours, theirs)).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "theme/a" to MergeOutcome.UNCHANGED,
                "theme/b" to MergeOutcome.OURS,
                "theme/c" to MergeOutcome.THEIRS,
                "theme/d" to MergeOutcome.CONFLICT,
                "theme/e" to MergeOutcome.SAME,
            ),
        )
    }

    @Test
    fun `an addition or a removal is a change like any other`() {
        val base = mapOf("theme/kept" to "1", "theme/gone-there" to "1", "theme/gone-here" to "1", "theme/edited-and-gone" to "1")
        val ours = mapOf("theme/kept" to "1", "theme/gone-there" to "1", "theme/edited-and-gone" to "2", "theme/new-here" to "1")
        val theirs = mapOf("theme/kept" to "1", "theme/gone-here" to "1", "theme/new-there" to "1")

        val merge = DraftMerge.classify(base, ours, theirs).associateBy { it.contentKey }

        assertThat(merge.mapValues { it.value.outcome }).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "theme/kept" to MergeOutcome.UNCHANGED,
                "theme/gone-there" to MergeOutcome.THEIRS,
                "theme/gone-here" to MergeOutcome.OURS,
                "theme/edited-and-gone" to MergeOutcome.CONFLICT,
                "theme/new-here" to MergeOutcome.OURS,
                "theme/new-there" to MergeOutcome.THEIRS,
            ),
        )
        assertThat(merge.getValue("theme/gone-there").let { Triple(it.ours, it.theirs, it.inTheirs) }).isEqualTo(Triple(null, MergeChange.REMOVED, false))
        assertThat(merge.getValue("theme/new-there").let { Triple(it.ours, it.theirs, it.inTheirs) }).isEqualTo(Triple(null, MergeChange.ADDED, true))
        assertThat(merge.getValue("theme/edited-and-gone").let { it.ours to it.theirs }).isEqualTo(MergeChange.MODIFIED to MergeChange.REMOVED)
    }

    @Test
    fun `without a base, a resource on one side only is that side's addition, and on both sides with other content a conflict`() {
        assertThat(outcomes(emptyMap(), mapOf("theme/x" to "1", "theme/y" to "1"), mapOf("theme/x" to "2", "theme/z" to "1"))).containsExactlyInAnyOrderEntriesOf(
            mapOf("theme/x" to MergeOutcome.CONFLICT, "theme/y" to MergeOutcome.OURS, "theme/z" to MergeOutcome.THEIRS),
        )
    }

    // 1.0.0 → 1.1.0 (the next version continued on it) and 1.0.0 → 1.0.1 (parallel work).
    private val bases = mapOf("1.0.0" to null, "1.1.0" to "1.0.0", "1.0.1" to "1.0.0")

    @Test
    fun `the merge base of two lines is the release both descend from, not the draft's own base`() {
        val contained = DraftMerge.contained("1.1.0", emptyList(), bases::get)

        assertThat(contained).containsExactlyInAnyOrder("1.1.0", "1.0.0")
        assertThat(DraftMerge.mergeBase("1.0.1", contained, bases::get)).isEqualTo("1.0.0")
        assertThat(DraftMerge.mergeBase("1.0.0", contained, bases::get)).`as`("a release on the draft's own line").isEqualTo("1.0.0")
    }

    @Test
    fun `a release merged in counts with its line, and lines with nothing in common have no base`() {
        val contained = DraftMerge.contained("1.1.0", listOf("1.0.1"), bases::get)
        assertThat(contained).contains("1.0.1")
        assertThat(DraftMerge.mergeBase("1.0.1", contained, bases::get)).isEqualTo("1.0.1")

        assertThat(DraftMerge.mergeBase("9.0.0", setOf("1.0.0"), mapOf("9.0.0" to null)::get)).isNull()
    }

    @Test
    fun `the merge base by lineage carries the fingerprint the line names it by, and a release naming no base ends its line`() {
        val lines = mapOf(
            "1.1.0" to DraftMerge.MadeFrom("1.0.0", "v5:aaa"),
            "1.0.1" to DraftMerge.MadeFrom("1.0.0", "v5:bbb"),
        )
        val contained = DraftMerge.contained("1.1.0", emptyList()) { lines[it]?.version }

        assertThat(DraftMerge.mergeBaseOf("1.0.1", contained, lines::get)).isEqualTo(DraftMerge.MadeFrom("1.0.0", "v5:bbb"))
        // 2.0.1 records no base: 2.0.0 below it is not taken for one.
        assertThat(DraftMerge.mergeBaseOf("2.0.1", setOf("2.0.0"), lines::get)).isNull()
    }
}

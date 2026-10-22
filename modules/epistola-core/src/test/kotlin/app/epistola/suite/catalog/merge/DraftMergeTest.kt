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

    @Test
    fun `a baseline recorded while the dependencies were one entry takes the base's dependency entries`() {
        val base = mapOf("theme/a" to "1", "catalog/defaultTheme" to "d", "catalog/presets" to "p", "catalogDependency/shared" to "s1")

        val legacy = mapOf("theme/a" to "held", "catalog/defaultTheme" to "d", "catalog/presets" to "p", "catalog/dependencies" to "all")
        assertThat(CatalogSetting.completeBaseline(legacy, base))
            .isEqualTo(mapOf("theme/a" to "held", "catalog/defaultTheme" to "d", "catalog/presets" to "p", "catalogDependency/shared" to "s1"))

        val beforeSettings = mapOf("theme/a" to "held")
        assertThat(CatalogSetting.completeBaseline(beforeSettings, base)).`as`("one from before settings were merged").isEqualTo(base + ("theme/a" to "held"))

        val current = mapOf("theme/a" to "held", "catalog/defaultTheme" to "d", "catalog/presets" to "p")
        assertThat(CatalogSetting.completeBaseline(current, base)).`as`("a dependency it did not hold stays absent").isEqualTo(current)
    }
}

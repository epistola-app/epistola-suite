// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The line a draft continues and where each release it lacks was made off it ([ReleaseLineage.behindLine]),
 * which the web UI draws above the list of releases a draft lacks.
 */
class BehindLineTest {

    private fun release(version: String, base: String? = null, merged: List<String> = emptyList()) = LineageRelease(
        version = version,
        fingerprint = "v6:$version",
        base = base?.let { ReleaseParent(it, "v6:$it") },
        merged = merged.map { ReleaseParent(it, "v6:$it") },
        contentHash = "v6:$version",
    )

    /**
     * The demo's catalog: 1.0.0 → 1.1.0 → 1.2.0 → 1.2.1 on the main line, fixes 1.0.1 (made on 1.0.0)
     * and 1.1.1 (made on 1.1.0); [extra] releases besides.
     */
    private fun demo(mergedInto120: List<String> = emptyList(), vararg extra: LineageRelease) = ReleaseLineage(
        (
            listOf(
                release("1.0.0"),
                release("1.1.0", "1.0.0"),
                release("1.2.0", "1.1.0", merged = mergedInto120),
                release("1.2.1", "1.2.0"),
                release("1.0.1", "1.0.0"),
                release("1.1.1", "1.1.0"),
            ) + extra
            ).associateBy { it.version },
    )

    /** The line of the next release on 1.2.1 that merged [merged], for what it is behind as 1.3.0. */
    private fun ReleaseLineage.nextLine(merged: List<String> = emptyList()): BehindLine = behindLine("1.2.1", merged, behind("1.2.1", merged, "1.3.0"))

    @Test
    fun `the demo's next release continues 1_0_0 to 1_2_1 and lacks a fix made on each of 1_0_0 and 1_1_0`() {
        val line = demo().nextLine()

        assertThat(line.line).containsExactly("1.0.0", "1.1.0", "1.2.0", "1.2.1")
        assertThat(line.behind).containsExactly(BehindBranch("1.0.1", madeOn = "1.0.0"), BehindBranch("1.1.1", madeOn = "1.1.0"))
        assertThat(line.joined).isEmpty()
    }

    @Test
    fun `one missing release draws the line back to the release it was made on, and no further`() {
        val line = demo(mergedInto120 = listOf("1.0.1")).nextLine()

        assertThat(line.behind).containsExactly(BehindBranch("1.1.1", madeOn = "1.1.0"))
        assertThat(line.line).`as`("1.0.0 is older than anything that hangs off the line").containsExactly("1.1.0", "1.2.0", "1.2.1")
    }

    @Test
    fun `a release merged into the line is joined where it was merged, not missing`() {
        // 1.0.2, a later fix on the 1.0 line, was made on 1.0.1, which 1.2.0 merged.
        val line = demo(mergedInto120 = listOf("1.0.1"), release("1.0.2", "1.0.1")).nextLine()

        assertThat(line.behind.map { it.version }).containsExactly("1.0.2", "1.1.1")
        assertThat(line.joined).containsExactly(JoinedRelease("1.0.1", into = "1.2.0"))
        assertThat(line.behind).`as`("made on the release merged in").contains(BehindBranch("1.0.2", madeOn = "1.0.1"))
        assertThat(line.line).containsExactly("1.1.0", "1.2.0", "1.2.1")
    }

    @Test
    fun `a release merged into the draft itself is joined to it`() {
        val lineage = demo()
        val line = lineage.behindLine("1.2.1", listOf("1.1.1"), lineage.behind("1.2.1", listOf("1.1.1"), "1.3.0"))

        assertThat(line.behind).containsExactly(BehindBranch("1.0.1", madeOn = "1.0.0"))
        assertThat(line.joined).containsExactly(JoinedRelease("1.1.1", into = null))
        assertThat(line.line).containsExactly("1.0.0", "1.1.0", "1.2.0", "1.2.1")
    }

    @Test
    fun `a missing release made on one this catalog lacks, or on none, hangs off nothing`() {
        // 1.1.5 was received from elsewhere, made on 0.9.0, which was never received here; 1.1.6 names no base.
        val line = demo(emptyList(), release("1.1.5", "0.9.0"), release("1.1.6")).nextLine()

        assertThat(line.behind).containsExactly(
            BehindBranch("1.0.1", madeOn = "1.0.0"),
            BehindBranch("1.1.1", madeOn = "1.1.0"),
            BehindBranch("1.1.5", madeOn = null),
            BehindBranch("1.1.6", madeOn = null),
        )
        assertThat(line.line).containsExactly("1.0.0", "1.1.0", "1.2.0", "1.2.1")
    }

    @Test
    fun `a missing release made on another missing one hangs off it, and the line reaches where that one was made`() {
        val line = demo(emptyList(), release("1.0.2", "1.0.1")).nextLine()

        assertThat(line.behind).containsExactly(
            BehindBranch("1.0.1", madeOn = "1.0.0"),
            BehindBranch("1.0.2", madeOn = "1.0.1"),
            BehindBranch("1.1.1", madeOn = "1.1.0"),
        )
        assertThat(line.line.first()).isEqualTo("1.0.0")
    }

    @Test
    fun `nothing hanging off the line still draws the release the draft continues from, and a draft from nothing has no line`() {
        val received = ReleaseLineage(listOf(release("1.0.0"), release("1.1.0", "1.0.0"), release("1.0.5", "0.9.0")).associateBy { it.version })
        assertThat(received.behindLine("1.1.0", emptyList(), listOf("1.0.5")).line).containsExactly("1.1.0")

        val fromNothing = demo().behindLine(null, emptyList(), listOf("1.0.0"))
        assertThat(fromNothing.line).isEmpty()
        assertThat(fromNothing.behind).containsExactly(BehindBranch("1.0.0", madeOn = null))
    }
}

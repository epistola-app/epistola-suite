// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReleaseLineageTest {

    private fun lineage(vararg releases: LineageRelease) = ReleaseLineage(releases.associateBy { it.version })

    private fun release(version: String, base: String? = null, baseFingerprint: String? = null, merged: List<String> = emptyList()) = LineageRelease(
        version = version,
        fingerprint = "v5:$version",
        base = base?.let { ReleaseParent(it, baseFingerprint) },
        merged = merged.map { ReleaseParent(it, "v5:$it") },
    )

    // 1.0.0 → 1.1.0 (the next version continued on it) and 1.0.0 → 1.0.1 (parallel work).
    private val twoLines = lineage(release("1.0.0"), release("1.1.0", "1.0.0", "v5:aaa"), release("1.0.1", "1.0.0", "v5:bbb"))

    @Test
    fun `the merge base of two lines is the release both descend from, not the draft's own base`() {
        val contained = twoLines.containedBy("1.1.0", emptyList())

        assertThat(contained).containsExactlyInAnyOrder("1.1.0", "1.0.0")
        assertThat(twoLines.closestIn("1.0.1", contained)).isEqualTo(ReleaseParent("1.0.0", "v5:bbb"))
        assertThat(twoLines.closestIn("1.0.0", contained)?.version).`as`("a release on the draft's own line").isEqualTo("1.0.0")
    }

    @Test
    fun `a release merged in counts with its line, and lines with nothing in common have no base`() {
        val contained = twoLines.containedBy("1.1.0", listOf("1.0.1"))
        assertThat(contained).contains("1.0.1")
        assertThat(twoLines.closestIn("1.0.1", contained)?.version).isEqualTo("1.0.1")

        assertThat(lineage(release("9.0.0"), release("1.0.0")).closestIn("9.0.0", setOf("1.0.0"))).isNull()
    }

    /** Lineage is by hash (ADR 0034): a parent is the release with its content hash, whatever its number here. */
    @Test
    fun `a parent named by its content hash is the release here that has it, under any number`() {
        val renumbered = lineage(
            LineageRelease("0.9.0", fingerprint = "v6:x", base = null, contentHash = "v6:x"),
            LineageRelease("1.1.0", fingerprint = "v6:y", base = ReleaseParent("1.0.0", "v6:x"), contentHash = "v6:y"),
        )
        assertThat(renumbered.parentsOf("1.1.0")).containsExactly(ReleaseParent("0.9.0", "v6:x"))
        assertThat(renumbered.closestIn("1.1.0", setOf("0.9.0"))?.version).isEqualTo("0.9.0")

        // A release recorded with its V5 fingerprint and its content hash is found by either.
        val old = lineage(LineageRelease("1.0.0", fingerprint = "v5:old", base = null, contentHash = "v6:x"), LineageRelease("1.1.0", "v6:y", ReleaseParent("1.0.0", "v6:x")))
        assertThat(old.parentsOf("1.1.0")).containsExactly(ReleaseParent("1.0.0", "v6:x"))
    }

    /** Several numbers may name one content (a notes-only re-release): a parent whose number has its hash stays. */
    @Test
    fun `a parent whose own number has its content hash stays that number`() {
        val reReleased = lineage(
            LineageRelease("1.0.0", "v6:x", null, contentHash = "v6:x"),
            LineageRelease("1.0.1", "v6:x", ReleaseParent("1.0.0", "v6:x"), contentHash = "v6:x"),
            LineageRelease("1.1.0", "v6:z", ReleaseParent("1.0.1", "v6:x"), contentHash = "v6:z"),
        )
        assertThat(reReleased.parentsOf("1.1.0")).containsExactly(ReleaseParent("1.0.1", "v6:x"))
        assertThat(reReleased.parentsOf("1.0.1")).containsExactly(ReleaseParent("1.0.0", "v6:x"))
    }

    /** A patch release (ADR 0034) sorts below the release of its core: a draft released as 1.0.1 is behind 1.0.1-municipality.1. */
    @Test
    fun `a draft is behind a patch release numbered below its own`() {
        val withPatch = lineage(release("1.0.0"), release("1.0.1-municipality.1", "1.0.0"))
        assertThat(withPatch.behind("1.0.0", emptyList(), "1.0.1")).containsExactly("1.0.1-municipality.1")
        assertThat(withPatch.behind("1.0.0", emptyList(), "1.0.0-municipality.9")).isEmpty()
    }

    @Test
    fun `a release naming no base ends its line`() {
        // 2.0.1 records no base: 2.0.0 below it is not taken for one.
        assertThat(lineage(release("2.0.0"), release("2.0.1")).closestIn("2.0.1", setOf("2.0.0"))).isNull()
    }

    /**
     * The demo's catalog: 1.0.0 → 1.1.0 → 1.2.0 → 1.2.1 on the main line, fixes 1.0.1 (from 1.0.0) and
     * 1.1.1 (from 1.1.0); the next release is on 1.2.1.
     */
    private fun demo(mergedInto120: List<String> = emptyList()) = lineage(
        release("1.0.0"),
        release("1.1.0", "1.0.0"),
        release("1.2.0", "1.1.0", merged = mergedInto120),
        release("1.2.1", "1.2.0"),
        release("1.0.1", "1.0.0"),
        release("1.1.1", "1.1.0"),
    )

    @Test
    fun `a draft is behind every fix its line does not contain, however long ago it was released`() {
        assertThat(demo().behind(base = "1.2.1", merged = emptyList(), number = "1.2.2")).containsExactly("1.0.1", "1.1.1")
    }

    @Test
    fun `a fix merged before a release is contained by every release after it`() {
        assertThat(demo(mergedInto120 = listOf("1.0.1")).behind(base = "1.2.1", merged = emptyList(), number = "1.3.0")).containsExactly("1.1.1")
    }

    @Test
    fun `a draft is not behind what it merged, nor what that release's line contains`() {
        val lineage = demo(mergedInto120 = listOf("1.0.1"))
        assertThat(lineage.behind(base = "1.1.0", merged = listOf("1.2.0"), number = "1.3.0")).containsExactly("1.1.1", "1.2.1")
        assertThat(lineage.closestIn("1.0.1", lineage.containedBy("1.2.1", emptyList()))?.version).`as`("merged, it is its own merge base").isEqualTo("1.0.1")
    }

    @Test
    fun `a release above the number a draft would be released as is not behind`() {
        assertThat(demo().behind(base = "1.0.0", merged = emptyList(), number = "1.0.2")).containsExactly("1.0.1")
    }

    @Test
    fun `a release on the line that records no lineage is taken to contain the releases below it`() {
        // Received from archives that named no base: nothing says what 1.1.0 contains.
        val unrecorded = lineage(release("1.0.0"), release("1.0.1"), release("1.1.0"), release("1.2.0"))
        assertThat(unrecorded.behind(base = "1.1.0", merged = emptyList(), number = "1.1.1")).isEmpty()
        assertThat(unrecorded.behind(base = "1.1.0", merged = emptyList(), number = "1.3.0")).containsExactly("1.2.0")
    }
}

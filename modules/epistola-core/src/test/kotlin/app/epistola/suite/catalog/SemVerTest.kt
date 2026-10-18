// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemVerTest {

    @Test
    fun `parses a valid version`() {
        val v = SemVer.parse("1.4.2")
        assertEquals(SemVer(1, 4, 2), v)
        assertEquals("1.4.2", v.toString())
    }

    @Test
    fun `parse trims surrounding whitespace`() {
        assertEquals(SemVer(2, 0, 0), SemVer.parse("  2.0.0 "))
    }

    @Test
    fun `parse rejects non-SemVer strings`() {
        assertThrows<IllegalArgumentException> { SemVer.parse("5.5") }
        assertThrows<IllegalArgumentException> { SemVer.parse("1") }
        assertThrows<IllegalArgumentException> { SemVer.parse("1.2.3-") }
        assertThrows<IllegalArgumentException> { SemVer.parse("1.2.3-a..b") }
        assertThrows<IllegalArgumentException> { SemVer.parse("v1.2.3") }
        assertThrows<IllegalArgumentException> { SemVer.parse("") }
    }

    @Test
    fun `parseOrNull returns null for legacy labels instead of throwing`() {
        assertNull(SemVer.parseOrNull("5.5"))
        assertNull(SemVer.parseOrNull("1"))
        assertNull(SemVer.parseOrNull(""))
        assertEquals(SemVer(5, 5, 0), SemVer.parseOrNull("5.5.0"))
    }

    @Test
    fun `negative components are rejected`() {
        assertThrows<IllegalArgumentException> { SemVer(-1, 0, 0) }
    }

    @Test
    fun `ordering compares major then minor then patch`() {
        assertTrue(SemVer.parse("1.2.0") < SemVer.parse("1.10.0"))
        assertTrue(SemVer.parse("2.0.0") > SemVer.parse("1.99.99"))
        assertTrue(SemVer.parse("1.2.3") > SemVer.parse("1.2.2"))
        assertEquals(0, SemVer.parse("1.2.3").compareTo(SemVer.parse("1.2.3")))
    }

    @Test
    fun `bumps reset lower components`() {
        assertEquals(SemVer(2, 0, 0), SemVer(1, 4, 7).bumpMajor())
        assertEquals(SemVer(1, 5, 0), SemVer(1, 4, 7).bumpMinor())
        assertEquals(SemVer(1, 4, 8), SemVer(1, 4, 7).bumpPatch())
    }

    @Test
    fun `a pre-release parses, renders and sorts below the release of its number, by SemVer precedence`() {
        val pre = SemVer.parse("2.0.0-redesign-2027.1")
        assertEquals(SemVer(2, 0, 0, "redesign-2027.1"), pre)
        assertEquals("2.0.0-redesign-2027.1", pre.toString())
        assertEquals(true, pre.isPreRelease)
        assertEquals(SemVer(2, 0, 0), pre.core)
        assertNull(SemVer.releaseOrNull("2.0.0-redesign-2027.1"))

        // SemVer 2.0.0 §11.4's own example, plus numeric identifiers compared as numbers.
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0")
        assertEquals(ordered, ordered.shuffled(java.util.Random(7)).sortedBy { SemVer.parse(it) })
        assertEquals(true, SemVer.parse("1.9.9") < SemVer.parse("2.0.0-redesign.1"))
        assertEquals(SemVer(2, 0, 1), SemVer.parse("2.0.0-x.1").bumpPatch())
    }
}

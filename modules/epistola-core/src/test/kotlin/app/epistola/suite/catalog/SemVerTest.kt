// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertThrows<IllegalArgumentException> { SemVer.parse("1.2.3-rc1") }
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

    /** A patch release (ADR 0034, contract `CatalogReleaseVersions`): `MAJOR.MINOR.PATCH-<namespace>.<N>`. */
    @Test
    fun `parses a patch release, and nothing else after the core`() {
        val patch = SemVer.parse("1.3.1-gemeente-x.1")
        assertEquals(SemVer(1, 3, 1, "gemeente-x", 1), patch)
        assertTrue(patch.isPatchRelease)
        assertEquals("1.3.1-gemeente-x.1", patch.toString())
        assertEquals(SemVer(1, 3, 1), patch.core)

        assertNull(SemVer.parseOrNull("1.3.1-gemeente-x"), "a label names a sequence number")
        assertNull(SemVer.parseOrNull("1.3.1-gemeente-x.0"), "the sequence counts from 1")
        assertNull(SemVer.parseOrNull("1.3.1-ab.1"), "a namespace has at least 3 characters")
        assertNull(SemVer.parseOrNull("1.3.1-Gemeente.1"), "a namespace is lowercase")
        assertNull(SemVer.parseOrNull("1.3.1+build.1"), "no build part")
        assertNull(SemVer.parseOrNull("1.3.1-rc.1.2"), "no other pre-release")
    }

    @Test
    fun `a patch release of any namespace parses, and nothing over 128 characters does`() {
        val namespace = "n" + "a".repeat(62)
        assertEquals(namespace, SemVer.parse("2147483647.2147483647.2147483647-$namespace.2147483647").patchNamespace)
        assertNull(SemVer.parseOrNull("1.0.0-$namespace" + "a" + ".1"), "a namespace has at most 63 characters")
        assertNull(SemVer.parseOrNull("1.0.0-${"a".repeat(60)}.1" + "0".repeat(129)), "longer than 128 characters")
    }

    @Test
    fun `a patch release sorts above the release it fixes and below the next`() {
        val patch = SemVer.parse("1.3.1-gemeente-x.1")
        assertTrue(patch > SemVer.parse("1.3.0"))
        assertTrue(patch < SemVer.parse("1.3.1"))
        assertTrue(patch < SemVer.parse("1.3.1-gemeente-x.2"))
        assertTrue(SemVer.parse("1.3.1-gemeente-x.2") < SemVer.parse("1.3.1-gemeente-x.10"), "the sequence is a number")
        assertTrue(SemVer.parse("1.3.1-bureau.9") < SemVer.parse("1.3.1-gemeente-x.1"), "labels as ASCII")
        assertEquals(listOf("1.3.0", "1.3.1-bureau.9", "1.3.1-gemeente-x.1", "1.3.1", "1.4.0"), listOf("1.4.0", "1.3.1", "1.3.1-gemeente-x.1", "1.3.0", "1.3.1-bureau.9").sortedBy(SemVer::parse))
    }

    @Test
    fun `bumping a patch release bumps its core`() {
        val patch = SemVer.parse("1.3.1-gemeente-x.1")
        assertEquals(SemVer(1, 3, 1), patch.bumpPatch())
        assertEquals(SemVer(1, 4, 0), patch.bumpMinor())
        assertEquals(SemVer(2, 0, 0), patch.bumpMajor())
    }

    @Test
    fun `a release is newer than a catalog's highest only when it sorts above it`() {
        assertTrue(SemVer.isNewer("1.1.0", null), "any release, for a catalog without one")
        assertTrue(SemVer.isNewer("1.1.0", "1.0.1"))
        assertTrue(SemVer.isNewer("1.0.1", "1.0.1-gemeente-x.1"), "the core above its patch release")
        assertFalse(SemVer.isNewer("1.0.1", "1.0.1"))
        assertFalse(SemVer.isNewer("1.0.1", "1.1.0"), "a release cut here above the source's keeps the catalog current")
        assertTrue(SemVer.isNewer("5.5", "5.4"), "a number that does not parse is newer when it differs")
        assertFalse(SemVer.isNewer("5.5", "5.5"))
    }
}

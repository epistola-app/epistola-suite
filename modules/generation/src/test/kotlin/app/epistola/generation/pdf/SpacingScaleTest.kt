package app.epistola.generation.pdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpacingScaleTest {

    // -----------------------------------------------------------------------
    // resolve()
    // -----------------------------------------------------------------------

    @Test
    fun `resolve returns 0 for step 0`() {
        assertEquals(0f, SpacingScale.resolve("0"))
    }

    @Test
    fun `resolve uses default base unit of 4pt`() {
        assertEquals(8f, SpacingScale.resolve("2"))
        assertEquals(12f, SpacingScale.resolve("3"))
        assertEquals(2f, SpacingScale.resolve("0.5"))
    }

    @Test
    fun `resolve with custom base unit`() {
        assertEquals(6f, SpacingScale.resolve("2", baseUnit = 3f))
        assertEquals(15f, SpacingScale.resolve("3", baseUnit = 5f))
    }

    @Test
    fun `resolve accepts arbitrary numeric multipliers`() {
        assertEquals(10f, SpacingScale.resolve("2.5"))
    }

    @Test
    fun `resolve returns 0 for invalid step`() {
        assertEquals(0f, SpacingScale.resolve("invalid"))
    }

    @Test
    fun `resolve covers all standard steps`() {
        val expected = mapOf(
            "0" to 0f,
            "0.5" to 2f,
            "1" to 4f,
            "1.5" to 6f,
            "2" to 8f,
            "3" to 12f,
            "4" to 16f,
            "5" to 20f,
            "6" to 24f,
            "8" to 32f,
            "10" to 40f,
            "12" to 48f,
            "16" to 64f,
        )
        for ((step, expectedPt) in expected) {
            assertEquals(expectedPt, SpacingScale.resolve(step), "Step $step should resolve to ${expectedPt}pt")
        }
    }

    // -----------------------------------------------------------------------
    // parseSp()
    // -----------------------------------------------------------------------

    @Test
    fun `parseSp parses 2sp with default base unit`() {
        assertEquals(8f, SpacingScale.parseSp("2sp"))
    }

    @Test
    fun `parseSp parses 0_5sp`() {
        assertEquals(2f, SpacingScale.parseSp("0.5sp"))
    }

    @Test
    fun `parseSp parses 0sp as zero`() {
        assertEquals(0f, SpacingScale.parseSp("0sp"))
    }

    @Test
    fun `parseSp with custom base unit`() {
        assertEquals(15f, SpacingScale.parseSp("3sp", baseUnit = 5f))
    }

    @Test
    fun `parseSp returns null for non-sp values`() {
        assertNull(SpacingScale.parseSp("12pt"))
        assertNull(SpacingScale.parseSp(""))
        assertNull(SpacingScale.parseSp("sp"))
    }

    @Test
    fun `parseSp supports arbitrary multiplier`() {
        assertEquals(10f, SpacingScale.parseSp("2.5sp"))
    }

    // -----------------------------------------------------------------------
    // STEPS invariants
    // -----------------------------------------------------------------------

    @Test
    fun `STEPS contains 13 entries`() {
        assertEquals(13, SpacingScale.STEPS.size)
    }

    @Test
    fun `STEPS are in ascending order`() {
        val values = SpacingScale.STEPS.values.toList()
        for (i in 1 until values.size) {
            assert(values[i] > values[i - 1]) {
                "Step ${SpacingScale.STEPS.keys.toList()[i]} (${values[i]}) should be greater than previous (${values[i - 1]})"
            }
        }
    }

    @Test
    fun `all STEPS produce whole or half-point values with default base unit`() {
        for ((step, multiplier) in SpacingScale.STEPS) {
            val pt = multiplier * SpacingScale.DEFAULT_BASE_UNIT
            val remainder = pt % 0.5f
            assertEquals(0f, remainder, "Step $step produces ${pt}pt which is not a multiple of 0.5pt")
        }
    }
}

package app.epistola.suite.fonts

import app.epistola.suite.fonts.model.FontVariantSource
import app.epistola.suite.fonts.queries.FaceRow
import app.epistola.suite.fonts.queries.pickBestFace
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure unit coverage for the nearest-weight face selection extracted from
 * `ResolveFontFaceHandler` into [pickBestFace]. No DB — exercises the
 * italic-preference + nearest-weight + tie-break-toward-heavier algorithm
 * directly.
 */
class ResolveFontFaceSelectionTest {

    private fun face(weight: Int, italic: Boolean) = FaceRow(
        weight = weight,
        italic = italic,
        source = FontVariantSource.CLASSPATH,
        assetKey = null,
        classpathLocation = "epistola/fonts/x/x-$weight${if (italic) "i" else ""}.ttf",
    )

    @Test
    fun `empty rows return null`() {
        assertThat(pickBestFace(emptyList(), weight = 400, italic = false)).isNull()
    }

    @Test
    fun `exact weight and italic match wins`() {
        val rows = listOf(face(400, false), face(700, false), face(400, true), face(700, true))
        assertThat(pickBestFace(rows, weight = 700, italic = false)).isEqualTo(face(700, false))
        assertThat(pickBestFace(rows, weight = 400, italic = true)).isEqualTo(face(400, true))
    }

    @Test
    fun `nearest weight by absolute distance is chosen when no exact match`() {
        val rows = listOf(face(300, false), face(900, false))
        // 500 → |500-300|=200 vs |500-900|=400 → 300 wins.
        assertThat(pickBestFace(rows, weight = 500, italic = false)).isEqualTo(face(300, false))
        // 800 → |800-300|=500 vs |800-900|=100 → 900 wins.
        assertThat(pickBestFace(rows, weight = 800, italic = false)).isEqualTo(face(900, false))
    }

    @Test
    fun `equal distance ties break toward the heavier weight`() {
        val rows = listOf(face(400, false), face(700, false))
        // 550 is equidistant (150) from 400 and 700 → heavier (700) wins.
        assertThat(pickBestFace(rows, weight = 550, italic = false)).isEqualTo(face(700, false))
    }

    @Test
    fun `requested italic is preferred over a nearer-weight upright face`() {
        val rows = listOf(face(400, false), face(900, true))
        // Even though 400-upright is nearer to weight 400, the italic
        // preference selects within the matching-italic set first.
        assertThat(pickBestFace(rows, weight = 400, italic = true)).isEqualTo(face(900, true))
    }

    @Test
    fun `falls back to the other italic set when none with requested italic`() {
        val rows = listOf(face(400, false), face(700, false))
        // Family ships no italic faces → fall back to upright, nearest weight.
        assertThat(pickBestFace(rows, weight = 700, italic = true)).isEqualTo(face(700, false))
    }
}

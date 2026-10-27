// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

import com.itextpdf.io.font.FontProgram
import com.itextpdf.io.font.FontProgramFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Checks each bundled system font file against the face it is registered as. The catalog addresses
 * files by name only, so a file holding the wrong face (Merriweather shipped with Regular and Italic,
 * and Bold and BoldItalic, swapped) or one that lost glyphs in an update rendered wrong without
 * anything failing.
 */
class SystemFontFilesTest {

    private val faces = SystemCatalogFaces.ALL

    private fun program(face: DeclaredSystemFace): FontProgram {
        val bytes = javaClass.classLoader.getResourceAsStream(face.classpathLocation)!!.use { it.readBytes() }
        // Uncached: iText's font cache is static and never evicts, and a parsed face is large.
        return FontProgramFactory.createFont(bytes, false)
    }

    @Test
    fun `every bundled file holds the weight and style it is registered as`() {
        val mismatches = faces.mapNotNull { face ->
            val names = program(face).fontNames
            val actualBold = names.fontWeight >= BOLD_WEIGHT
            val declaredBold = face.weight >= BOLD_WEIGHT
            if (actualBold == declaredBold && names.isItalic == face.italic) {
                null
            } else {
                "${face.classpathLocation} is registered as (${face.weight}, italic=${face.italic}) " +
                    "but holds ${names.fontName} (weight ${names.fontWeight}, italic=${names.isItalic})"
            }
        }

        assertThat(mismatches).isEmpty()
    }

    @Test
    fun `every bundled file covers European Latin text, apart from the listed gaps`() {
        val uncovered = faces.mapNotNull { face ->
            val program = program(face)
            val expectedGaps = KNOWN_GAPS[face.slug].orEmpty()
            val missing = REQUIRED_CODE_POINTS.filter { cp -> program.getGlyph(cp)?.code?.let { it != 0 } != true }
                .filterNot { it in expectedGaps }
            missing.takeIf { it.isNotEmpty() }?.let {
                "${face.classpathLocation} lacks ${it.joinToString(" ") { cp -> "U+%04X".format(cp) }}"
            }
        }

        assertThat(uncovered).isEmpty()
    }

    @Test
    fun `listed gaps are still gaps`() {
        // Keeps KNOWN_GAPS honest: a gap a font update has filled must leave the list.
        val filled = faces.flatMap { face ->
            val program = program(face)
            KNOWN_GAPS[face.slug].orEmpty()
                .filter { cp -> program.getGlyph(cp)?.code?.let { it != 0 } == true }
                .map { cp -> "${face.classpathLocation} now has U+%04X".format(cp) }
        }

        assertThat(filled).isEmpty()
    }

    private companion object {
        const val BOLD_WEIGHT = 600

        /** Latin-1 Supplement and Latin Extended-A (every European Latin letter), plus the punctuation letters use. */
        val REQUIRED_CODE_POINTS: List<Int> = (0x20..0x7E) + (0xA1..0xFF) + (0x100..0x17F) +
            listOf(0x2013, 0x2014, 0x2018, 0x2019, 0x201A, 0x201C, 0x201D, 0x201E, 0x2022, 0x2026, 0x2030, 0x20AC)

        /**
         * Characters a bundled family does not have. Text using one of these renders a blank glyph,
         * and fails to render as PDF/A. U+00AD (soft hyphen) is harmless: the layout handles it and never
         * draws it. The Dutch Ĳ/ĳ gaps are real for content that uses the single-character form.
         */
        val KNOWN_GAPS: Map<String, Set<Int>> = mapOf(
            "inter" to setOf(0x00AD, 0x0149),
            "merriweather" to setOf(0x00AD, 0x0149),
            "lora" to setOf(0x00AD, 0x0138),
            "jetbrains-mono" to setOf(0x0132, 0x0133),
            "source-serif-4" to setOf(0x012C, 0x012D, 0x0132, 0x0133, 0x0166, 0x0167),
        )
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import java.nio.file.Files
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * iText's `SvgConverter` fetches URLs and reads local files that an SVG references unless it is
 * given locked-down properties. `SafeSvgConversion` in `modules/generation` is the one place that
 * builds those, so it is the only production source allowed to touch the converter or build its
 * properties.
 */
class SvgConversionUsageTest {
    private val banned = Regex("""\b(SvgConverter|SvgConverterProperties|ISvgConverterProperties)\b""")
    private val allowed = "SafeSvgConversion.kt"

    @Test
    fun `only SafeSvgConversion reaches iText's SVG converter`() {
        val files = RepoSources.mainKotlinFiles()
        assertTrue(files.any { it.name == allowed }, "$allowed not found — the scan would pass vacuously")

        val offenders = files
            .filter { it.name != allowed }
            .filter { banned.containsMatchIn(RepoSources.stripComments(Files.readString(it))) }
            .map(RepoSources::relativize)

        assertEquals(emptyList(), offenders, "Convert SVG through SafeSvgConversion, never SvgConverter directly")
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class TextCleanupTest {

    @Test
    fun `Word's line and page breaks and the Unicode separators become a line break`() {
        assertEquals("a\nb\nc\nd\ne", TextCleanup.clean("a\u000Bb\u000Cc d e"))
    }

    @Test
    fun `Word's hyphens become a hyphen and a soft hyphen`() {
        assertEquals("06-123 onder­werp", TextCleanup.clean("06\u001E123 onder\u001Fwerp"))
    }

    @Test
    fun `Word's Symbol and Wingdings bullets become a bullet`() {
        assertEquals("•\t•\t•\t•\t•", TextCleanup.clean("\t\t\t\t"))
    }

    @Test
    fun `other control characters are removed, tab and line endings kept`() {
        assertEquals("a\tb\r\nc", TextCleanup.clean("a\u0000\tb\u001C\r\n\u0007c"))
    }

    @Test
    fun `text without any of them is returned as it is`() {
        val text = "Gewone tekst — met “aanhalingstekens”, € en 😀"
        assertSame(text, TextCleanup.clean(text))
    }
}

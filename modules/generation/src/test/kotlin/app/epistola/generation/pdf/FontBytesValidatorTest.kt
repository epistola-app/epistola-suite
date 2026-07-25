// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Guards [FontBytesValidator] — the upload gate that rejects anything the PDF
 * renderer cannot actually embed. A real bundled TTF must pass; garbage,
 * empty, and a WOFF2-shaped binary must be rejected with a human-readable
 * reason (not silently stored and then rendered as the fallback).
 */
class FontBytesValidatorTest {

    private val liberationRegularBytes: ByteArray =
        FontBytesValidatorTest::class.java.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")!!.readBytes()

    @Test
    fun `a real embeddable TTF is accepted`() {
        assertNull(FontBytesValidator.rejectionReason(liberationRegularBytes))
    }

    @Test
    fun `garbage bytes are rejected`() {
        val reason = FontBytesValidator.rejectionReason(byteArrayOf(1, 2, 3, 4))
        assertNotNull(reason, "Garbage bytes must produce a rejection reason")
    }

    @Test
    fun `empty bytes are rejected`() {
        val reason = FontBytesValidator.rejectionReason(ByteArray(0))
        assertNotNull(reason, "Empty bytes must produce a rejection reason")
    }

    @Test
    fun `a WOFF2-shaped binary is rejected`() {
        // WOFF2 magic "wOF2" then nonsense — not an sfnt, must be rejected.
        val woff2 = "wOF2".toByteArray() + byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
        val reason = FontBytesValidator.rejectionReason(woff2)
        assertNotNull(reason, "A WOFF2-shaped binary must produce a rejection reason")
    }
}

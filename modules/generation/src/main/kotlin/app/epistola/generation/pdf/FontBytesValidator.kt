// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import com.itextpdf.io.font.PdfEncodings
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.font.PdfFontFactory.EmbeddingStrategy

/**
 * Validates that uploaded font bytes are something the PDF renderer can
 * actually embed. This is the exact path [FontCache] uses at render time
 * (`createFont(bytes, IDENTITY_H, FORCE_EMBEDDED)`), so a font that passes
 * here is guaranteed embeddable later — closing the silent footgun where a
 * non-sfnt upload (e.g. WOFF2) or an embedding-restricted font stored fine
 * and then rendered as the built-in fallback with no signal.
 *
 * One check covers both failure modes: `FORCE_EMBEDDED` throws on a
 * non-sfnt/corrupt binary AND on a font whose OS/2 `fsType` forbids
 * embedding.
 */
object FontBytesValidator {

    /** @return null when the bytes are an embeddable TTF/OTF, else a human-readable rejection reason. */
    fun rejectionReason(bytes: ByteArray): String? = try {
        PdfFontFactory.createFont(bytes, PdfEncodings.IDENTITY_H, EmbeddingStrategy.FORCE_EMBEDDED)
        null
    } catch (e: Exception) {
        "Not a valid embeddable font. Upload an unrestricted TTF or OTF file " +
            "(WOFF/WOFF2 and embedding-restricted fonts are not supported). [${e.message}]"
    }
}

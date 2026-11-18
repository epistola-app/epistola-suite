// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

/**
 * Metadata to embed in the generated PDF document.
 * Used for both PDF info dictionary and XMP metadata (required by PDF/A).
 */
data class PdfMetadata(
    val title: String? = null,
    val author: String? = null,
    val subject: String? = null,
    val creator: String = "Epistola Suite",
    /**
     * Engine version string embedded in PDF metadata for traceability.
     * Format: `"epistola-gen-<defaults-version>+itext-<itext-version>"`
     * e.g., `"epistola-gen-8+itext-9.8.0"`, the iText version read from the jar
     */
    val engineVersion: String? = null,
    /**
     * Where the document came from, written to its XMP ([GenerationContextXmp]) with the render
     * time, locale and [engineVersion] it was rendered with. Null writes none.
     */
    val generationContext: GenerationContext? = null,
)

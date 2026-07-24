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
     * e.g., `"epistola-gen-1+itext-9.5.0"`
     */
    val engineVersion: String? = null,
    /**
     * BCP 47 language tag for the document (e.g., "nl-NL", "en-US").
     * Written to the PDF catalog /Lang entry for accessibility (WCAG PDF16).
     */
    val language: String = "nl-NL",
)

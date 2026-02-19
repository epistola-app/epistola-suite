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
)

package app.epistola.suite.documents.model

/**
 * Formats available for batch download assembly.
 */
enum class BatchDownloadFormat {
    /** ZIP archive containing individual PDF files. */
    ZIP,

    /** Single PDF created by merging all individual PDFs. */
    MERGED_PDF,
}

package app.epistola.generation.pdf

import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.multipdf.PDFMergerUtility
import java.io.File
import java.io.OutputStream

/**
 * Merges multiple PDF files into a single output stream using Apache PDFBox.
 * Uses temp-file-only stream cache to keep heap usage minimal for large merges.
 */
object PdfMergerUtil {

    fun mergePdfs(sources: List<File>, output: OutputStream) {
        require(sources.isNotEmpty()) { "At least one source PDF is required" }

        val merger = PDFMergerUtility()
        merger.destinationStream = output
        sources.forEach { merger.addSource(it) }
        merger.mergeDocuments(IOUtils.createTempFileOnlyStreamCache())
    }
}

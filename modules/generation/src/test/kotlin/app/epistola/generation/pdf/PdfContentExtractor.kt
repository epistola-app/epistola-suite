package app.epistola.generation.pdf

import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import com.itextpdf.kernel.pdf.canvas.parser.listener.LocationTextExtractionStrategy
import java.io.ByteArrayInputStream

/**
 * Extracts text content from a PDF for regression testing.
 *
 * Uses iText's [LocationTextExtractionStrategy] to extract text with spatial awareness
 * (text is ordered by position on the page, not by internal PDF stream order).
 * This captures both content correctness and basic layout structure.
 */
object PdfContentExtractor {
    /**
     * Extracts all text from all pages of a PDF document.
     * Each page is separated by a "--- PAGE N ---" header.
     *
     * @param pdfBytes The raw PDF bytes
     * @return A normalized text representation suitable for baseline comparison
     */
    fun extract(pdfBytes: ByteArray): String {
        val reader = PdfReader(ByteArrayInputStream(pdfBytes))
        val document = PdfDocument(reader)

        val result = StringBuilder()
        val pageCount = document.numberOfPages

        for (page in 1..pageCount) {
            if (page > 1) result.append("\n")
            result.append("--- PAGE $page ---\n")

            val strategy = LocationTextExtractionStrategy()
            val text = PdfTextExtractor.getTextFromPage(document.getPage(page), strategy)
            result.append(text.trimEnd())
            result.append("\n")
        }

        document.close()
        return result.toString().trimEnd() + "\n"
    }
}

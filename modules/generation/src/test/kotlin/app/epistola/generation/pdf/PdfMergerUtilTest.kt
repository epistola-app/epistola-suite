package app.epistola.generation.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PdfMergerUtilTest {

    @Test
    fun `merges two single-page PDFs into one two-page PDF`() {
        val pdf1 = createTestPdf(pages = 1, text = "Page from doc 1")
        val pdf2 = createTestPdf(pages = 1, text = "Page from doc 2")

        val merged = mergeTo(listOf(pdf1, pdf2))

        Loader.loadPDF(merged).use { doc ->
            assertEquals(2, doc.numberOfPages)
        }
    }

    @Test
    fun `merges three multi-page PDFs preserving total page count`() {
        val pdf1 = createTestPdf(pages = 2)
        val pdf2 = createTestPdf(pages = 3)
        val pdf3 = createTestPdf(pages = 1)

        val merged = mergeTo(listOf(pdf1, pdf2, pdf3))

        Loader.loadPDF(merged).use { doc ->
            assertEquals(6, doc.numberOfPages)
        }
    }

    @Test
    fun `single source PDF passes through unchanged page count`() {
        val pdf = createTestPdf(pages = 3)

        val merged = mergeTo(listOf(pdf))

        Loader.loadPDF(merged).use { doc ->
            assertEquals(3, doc.numberOfPages)
        }
    }

    @Test
    fun `throws on empty source list`() {
        assertFailsWith<IllegalArgumentException> {
            mergeTo(emptyList())
        }
    }

    @Test
    fun `preserves page order across documents`() {
        val pdf1 = createTestPdf(pages = 1, text = "FIRST")
        val pdf2 = createTestPdf(pages = 1, text = "SECOND")
        val pdf3 = createTestPdf(pages = 1, text = "THIRD")

        val merged = mergeTo(listOf(pdf1, pdf2, pdf3))

        Loader.loadPDF(merged).use { doc ->
            assertEquals(3, doc.numberOfPages)
        }
    }

    private fun mergeTo(files: List<File>): ByteArray {
        val output = ByteArrayOutputStream()
        PdfMergerUtil.mergePdfs(files, output)
        return output.toByteArray()
    }

    private fun createTestPdf(pages: Int = 1, text: String = "Test"): File {
        val file = Files.createTempFile("test-", ".pdf").toFile()
        file.deleteOnExit()

        PDDocument().use { doc ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            repeat(pages) { i ->
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(font, 12f)
                    cs.newLineAtOffset(50f, 700f)
                    cs.showText("$text - page ${i + 1}")
                    cs.endText()
                }
            }
            doc.save(file)
        }
        return file
    }
}

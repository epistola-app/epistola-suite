package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import com.itextpdf.pdfa.PdfADocument
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatermarkRenderingTest {
    private val renderer = DirectPdfRenderer()

    private fun textDocument(text: String): TemplateDocument {
        val rootId = "root-1"
        val rootSlotId = "slot-root"
        val textId = "text-1"
        val textNode = Node(
            id = textId,
            type = "text",
            props = mapOf(
                "content" to mapOf(
                    "type" to "doc",
                    "content" to listOf(
                        mapOf(
                            "type" to "paragraph",
                            "content" to listOf(mapOf("type" to "text", "text" to text)),
                        ),
                    ),
                ),
            ),
        )
        return TemplateDocument(
            root = rootId,
            nodes = mapOf(
                rootId to Node(id = rootId, type = "root", slots = listOf(rootSlotId)),
                textId to textNode,
            ),
            slots = mapOf(rootSlotId to Slot(id = rootSlotId, nodeId = rootId, name = "children", children = listOf(textId))),
        )
    }

    private fun render(document: TemplateDocument, pdfaCompliant: Boolean, watermarkText: String?): ByteArray {
        val output = ByteArrayOutputStream()
        renderer.render(document, emptyMap(), output, pdfaCompliant = pdfaCompliant, watermarkText = watermarkText)
        return output.toByteArray()
    }

    private fun pageTexts(pdfBytes: ByteArray): List<String> = PdfDocument(PdfReader(ByteArrayInputStream(pdfBytes))).use { pdf ->
        (1..pdf.numberOfPages).map { PdfTextExtractor.getTextFromPage(pdf.getPage(it)) }
    }

    @Test
    fun `watermark text appears on the page when requested`() {
        val pdfBytes = render(textDocument("Body content"), pdfaCompliant = true, watermarkText = "PREVIEW")
        assertTrue(pageTexts(pdfBytes).all { it.contains("PREVIEW") }, "every page should carry the watermark")
    }

    @Test
    fun `watermark is stamped as header, footer and centre ghost`() {
        // The handler draws the mark three times per page — header band, footer band,
        // and a faint centre ghost — so a preview both signals "preview" and brands.
        val pdfBytes = render(textDocument("Body content"), pdfaCompliant = true, watermarkText = "PREVIEW")
        val occurrencesPerPage = pageTexts(pdfBytes).map { page -> "PREVIEW".toRegex().findAll(page).count() }
        assertTrue(
            occurrencesPerPage.all { it >= 3 },
            "every page should carry the watermark three times (header, footer, centre ghost), got $occurrencesPerPage",
        )
    }

    @Test
    fun `no watermark text when not requested`() {
        val pdfBytes = render(textDocument("Body content"), pdfaCompliant = true, watermarkText = null)
        assertFalse(pageTexts(pdfBytes).any { it.contains("PREVIEW") }, "final output must not be watermarked")
    }

    @Test
    fun `watermark does not change pagination`() {
        // The watermark is painted on END_PAGE and must never participate in layout,
        // so a watermarked render must produce exactly the same page count as the
        // un-watermarked one — this is the core "preview matches final" guarantee.
        val document = textDocument("Body content")
        val withMark = pageTexts(render(document, pdfaCompliant = true, watermarkText = "PREVIEW")).size
        val withoutMark = pageTexts(render(document, pdfaCompliant = true, watermarkText = null)).size
        assertEquals(withoutMark, withMark)
    }

    @Test
    fun `watermarked PDF-A render is still a valid PDF-A document`() {
        val pdfBytes = render(textDocument("Body content"), pdfaCompliant = true, watermarkText = "PREVIEW")
        // Opening as a PdfADocument validates the conformance plumbing survives the
        // transparent watermark overlay (PDF/A-2 permits transparency).
        PdfADocument(PdfReader(ByteArrayInputStream(pdfBytes)), com.itextpdf.kernel.pdf.PdfWriter(ByteArrayOutputStream())).use { pdf ->
            assertTrue(pdf.numberOfPages >= 1)
        }
    }
}

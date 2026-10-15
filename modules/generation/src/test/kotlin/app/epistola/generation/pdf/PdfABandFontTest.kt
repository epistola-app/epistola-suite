// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Page headers, page footers and the address window are painted on their own iText `Canvas`,
 * which does not inherit the document's default font. Unstyled text there fell back to iText's
 * non-embedded Helvetica: iText 9.7.1 let that through as a non-compliant PDF/A, iText 9.8.0
 * rejects any non-ASCII glyph in it ("The 'é' glyph isn't defined in embedded font").
 */
class PdfABandFontTest {

    private val renderer = DirectPdfRenderer()

    private val accented = "José Gómez, kenmerk ë á € 10"

    private fun textNode(id: String, text: String) = Node(
        id = id,
        type = "text",
        props = mapOf(
            "content" to mapOf(
                "type" to "doc",
                "content" to listOf(
                    mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to text))),
                ),
            ),
        ),
    )

    private fun bandDocument(bandType: String) = TemplateDocument(
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("slot-root")),
            "band" to Node(id = "band", type = bandType, slots = listOf("slot-band"), props = mapOf("height" to "30pt")),
            "band-text" to textNode("band-text", accented),
            "body-text" to textNode("body-text", "Body"),
        ),
        slots = mapOf(
            "slot-root" to Slot(id = "slot-root", nodeId = "root", name = "children", children = listOf("band", "body-text")),
            "slot-band" to Slot(id = "slot-band", nodeId = "band", name = "children", children = listOf("band-text")),
        ),
    )

    private fun addressDocument() = TemplateDocument(
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("slot-root")),
            "addressblock" to Node(
                id = "addressblock",
                type = "addressblock",
                slots = listOf("slot-address", "slot-aside"),
                props = mapOf("top" to 45, "sideDistance" to 20, "addressWidth" to 85, "height" to 45),
            ),
            "address-text" to textNode("address-text", accented),
            "aside-text" to textNode("aside-text", "Aside"),
            "body-text" to textNode("body-text", "Body"),
        ),
        slots = mapOf(
            "slot-root" to Slot(id = "slot-root", nodeId = "root", name = "children", children = listOf("addressblock", "body-text")),
            "slot-address" to Slot(id = "slot-address", nodeId = "addressblock", name = "address", children = listOf("address-text")),
            "slot-aside" to Slot(id = "slot-aside", nodeId = "addressblock", name = "aside", children = listOf("aside-text")),
        ),
    )

    private fun assertRendersEmbedded(document: TemplateDocument) {
        val bytes = ByteArrayOutputStream()
            .also { renderer.render(document, emptyMap(), it, pdfaCompliant = true) }
            .toByteArray()

        assertContains(PdfContentExtractor.extract(bytes), "José Gómez")
        assertEquals(emptyList(), nonEmbeddedFonts(bytes))
    }

    private fun nonEmbeddedFonts(bytes: ByteArray): List<String> = PdfDocument(PdfReader(ByteArrayInputStream(bytes))).use { pdf ->
        (1..pdf.numberOfPages).flatMap { pageNumber ->
            val fonts = pdf.getPage(pageNumber).resources.getResource(PdfName.Font) ?: return@flatMap emptyList()
            fonts.keySet().mapNotNull { key ->
                val font = fonts.getAsDictionary(key)
                val descriptor = font.getAsDictionary(PdfName.FontDescriptor)
                    ?: font.getAsArray(PdfName.DescendantFonts)?.getAsDictionary(0)?.getAsDictionary(PdfName.FontDescriptor)
                val embedded = descriptor != null &&
                    listOf(PdfName.FontFile, PdfName.FontFile2, PdfName.FontFile3).any(descriptor::containsKey)
                if (embedded) null else "${font.getAsName(PdfName.BaseFont)} on page $pageNumber"
            }
        }
    }

    @Test
    fun `accented text in a page header renders as PDF A with embedded fonts`() = assertRendersEmbedded(bandDocument("pageheader"))

    @Test
    fun `accented text in a page footer renders as PDF A with embedded fonts`() = assertRendersEmbedded(bandDocument("pagefooter"))

    @Test
    fun `accented text in the address window renders as PDF A with embedded fonts`() = assertRendersEmbedded(addressDocument())
}

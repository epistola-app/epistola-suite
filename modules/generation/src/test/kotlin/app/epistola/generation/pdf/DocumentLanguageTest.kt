// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.generation.RenderCulture
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A document's `/Lang` (WCAG PDF16) is the language it was rendered in, so a screen reader reads
 * it with the right voice: the render culture's locale, never a fixed language.
 */
class DocumentLanguageTest {

    private val renderer = DirectPdfRenderer()

    @Test
    fun `the document language is the locale it was rendered in`() {
        for (pdfa in listOf(true, false)) {
            assertEquals("de-DE", langOf(render(singlePass, RenderCulture(locale = Locale.forLanguageTag("de-DE")), pdfa)), "pdfa=$pdfa")
            assertEquals("nl-NL", langOf(render(singlePass, RenderCulture(locale = Locale.forLanguageTag("nl-NL")), pdfa)), "pdfa=$pdfa")
        }
    }

    @Test
    fun `a render without a culture is in the default locale`() {
        assertEquals(RenderCulture.DEFAULT.locale.toLanguageTag(), langOf(render(singlePass, RenderCulture.DEFAULT, pdfa = true)))
    }

    @Test
    fun `a document rendered in two passes for its page total has the language too`() {
        assertTrue(TwoPassAnalyzer.requiresTwoPassRendering(twoPass), "the page total takes two passes")
        assertEquals("fr-FR", langOf(render(twoPass, RenderCulture(locale = Locale.forLanguageTag("fr-FR")), pdfa = true)))
    }

    private fun render(document: TemplateDocument, culture: RenderCulture, pdfa: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        renderer.render(document = document, data = emptyMap(), outputStream = out, metadata = PdfMetadata(title = "Letter"), pdfaCompliant = pdfa, culture = culture)
        return out.toByteArray()
    }

    private fun langOf(pdf: ByteArray): String? = PdfDocument(PdfReader(ByteArrayInputStream(pdf))).use { it.catalog.lang?.toUnicodeString() }

    private fun documentShowing(expression: String): TemplateDocument {
        val text = Node(
            id = "n-text",
            type = "text",
            props = mapOf(
                "content" to mapOf(
                    "type" to "doc",
                    "content" to listOf(
                        mapOf(
                            "type" to "paragraph",
                            "content" to listOf(mapOf("type" to "text", "text" to "Page "), mapOf("type" to "expression", "attrs" to mapOf("expression" to expression))),
                        ),
                    ),
                ),
            ),
        )
        return TemplateDocument(
            root = "n-root",
            nodes = mapOf("n-root" to Node(id = "n-root", type = "root", slots = listOf("s-root")), text.id to text),
            slots = mapOf("s-root" to Slot(id = "s-root", nodeId = "n-root", name = "children", children = listOf(text.id))),
        )
    }

    private val singlePass = documentShowing("sys.render.time")
    private val twoPass = documentShowing("sys.pages.total")
}

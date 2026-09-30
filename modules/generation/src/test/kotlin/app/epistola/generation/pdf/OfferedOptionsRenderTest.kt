// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.PageSettings
import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.EventType
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData
import com.itextpdf.kernel.pdf.canvas.parser.data.PathRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #1027: options the editor and the component/style registries offer, which the renderer
 * ignored. From rendering defaults V5 they take effect; earlier versions render as they did.
 */
class OfferedOptionsRenderTest {

    // -----------------------------------------------------------------------
    // hideOnFirstPage on a page header
    // -----------------------------------------------------------------------

    private fun headerHiddenOnFirstPage(): TemplateDocument {
        val doc = BandDoc()
        return doc.build(
            doc.header("header", doc.text("header-text", "HEADER"), props = mapOf("hideOnFirstPage" to true)),
            *doc.body("body", 30).toTypedArray(),
        )
    }

    @Test
    fun `a header with hideOnFirstPage draws nothing on page 1 and runs from page 2`() {
        val pages = drawnText(renderBands(headerHiddenOnFirstPage(), renderingDefaults = RenderingDefaults.V5))

        assertTrue(pages.size >= 2, "The fixture must span pages")
        assertFalse("HEADER" in pages.pageText(1), "page 1 must not carry the header")
        assertTrue("HEADER" in pages.pageText(2), "page 2 must carry the header")
    }

    @Test
    fun `before V5 a header ignores hideOnFirstPage`() {
        val pages = drawnText(renderBands(headerHiddenOnFirstPage(), renderingDefaults = RenderingDefaults.V4))

        assertTrue("HEADER" in pages.pageText(1))
    }

    // -----------------------------------------------------------------------
    // letterSpacing
    // -----------------------------------------------------------------------

    private fun spacedText(): TemplateDocument {
        val doc = BandDoc()
        val base = doc.build(doc.text("spaced", "SPACED"))
        val spaced = base.nodes.getValue("spaced").copy(styles = mapOf("letterSpacing" to "2pt"))
        return base.copy(nodes = base.nodes + ("spaced" to spaced))
    }

    @Test
    fun `letterSpacing sets the character spacing of the text`() {
        val spacing = characterSpacing(renderBands(spacedText(), renderingDefaults = RenderingDefaults.V5), "SPACED")

        assertEquals(2f, spacing)
    }

    @Test
    fun `letterSpacing inherited from the document styles reaches the text`() {
        val doc = BandDoc()
        val output = java.io.ByteArrayOutputStream()
        DirectPdfRenderer().render(
            document = doc.build(doc.text("plain", "INHERITED")),
            data = emptyMap(),
            outputStream = output,
            resolvedTheme = ResolvedTheme(documentStyles = mapOf("letterSpacing" to "1.5pt")),
            renderingDefaults = RenderingDefaults.V5,
        )

        assertEquals(1.5f, characterSpacing(output.toByteArray(), "INHERITED"))
    }

    @Test
    fun `before V5 letterSpacing is ignored`() {
        val spacing = characterSpacing(renderBands(spacedText(), renderingDefaults = RenderingDefaults.V4), "SPACED")

        assertEquals(0f, spacing)
    }

    // -----------------------------------------------------------------------
    // Page backgroundColor
    // -----------------------------------------------------------------------

    private fun tintedPages(): TemplateDocument {
        val doc = BandDoc()
        return doc.build(*doc.body("body", 30).toTypedArray())
            .copy(pageSettingsOverride = PageSettings(backgroundColor = "#ffeeaa"))
    }

    @Test
    fun `a page backgroundColor fills every page`() {
        val pdf = renderBands(tintedPages(), renderingDefaults = RenderingDefaults.V5)
        val fills = pageFills(pdf)
        val pageCount = drawnText(pdf).size

        assertTrue(pageCount >= 2, "The fixture must span pages")
        (1..pageCount).forEach { page ->
            assertTrue(TINT in fills[page].orEmpty(), "page $page must be filled with the background; fills=${fills[page]}")
        }
    }

    @Test
    fun `the page background is an artifact, so PDF-UA readers skip it`() {
        var backgroundFills = 0
        processPages(renderBands(tintedPages(), renderingDefaults = RenderingDefaults.V5), setOf(EventType.RENDER_PATH)) { _, data, _ ->
            val info = data as PathRenderInfo
            val rgb = (info.fillColor as? DeviceRgb)?.colorValue?.map { Math.round(it * 100f) / 100f }
            if (info.operation and PathRenderInfo.FILL != 0 && rgb == TINT) {
                backgroundFills++
                assertTrue(info.canvasTagHierarchy.any { it.role == com.itextpdf.kernel.pdf.PdfName.Artifact }, "background fill must be marked as an artifact")
            }
        }
        assertTrue(backgroundFills > 0)
    }

    @Test
    fun `a page background renders under PDF-A`() {
        val output = java.io.ByteArrayOutputStream()
        DirectPdfRenderer().render(tintedPages(), emptyMap(), output, pdfaCompliant = true, renderingDefaults = RenderingDefaults.V5)

        assertTrue(output.size() > 0)
    }

    @Test
    fun `before V5 a page backgroundColor is ignored`() {
        val fills = pageFills(renderBands(tintedPages(), renderingDefaults = RenderingDefaults.V4))

        fills.forEach { (page, colors) -> assertFalse(TINT in colors, "page $page must not be filled; fills=$colors") }
    }

    // -----------------------------------------------------------------------
    // PDF inspection
    // -----------------------------------------------------------------------

    /** `#ffeeaa`, rounded like [pageFills] rounds. */
    private val TINT = listOf(1f, 0.93f, 0.67f)

    /** The character spacing the first chunk containing [text] was drawn with. */
    private fun characterSpacing(pdfBytes: ByteArray, text: String): Float {
        var found: Float? = null
        processPages(pdfBytes, setOf(EventType.RENDER_TEXT)) { _, data, _ ->
            val info = data as TextRenderInfo
            if (found == null && text in info.text) found = info.charSpacing
        }
        return found ?: error("'$text' was not drawn")
    }

    /** Per page, the RGB fill colours of every filled path, rounded to two decimals. */
    private fun pageFills(pdfBytes: ByteArray): Map<Int, List<List<Float>>> {
        val fills = mutableMapOf<Int, MutableList<List<Float>>>()
        processPages(pdfBytes, setOf(EventType.RENDER_PATH)) { page, data, _ ->
            val info = data as PathRenderInfo
            if (info.operation and PathRenderInfo.FILL != 0) {
                (info.fillColor as? DeviceRgb)?.colorValue?.let { rgb ->
                    fills.getOrPut(page) { mutableListOf() } += rgb.map { Math.round(it * 100f) / 100f }
                }
            }
        }
        return fills
    }

    private fun processPages(pdfBytes: ByteArray, events: Set<EventType>, onEvent: (Int, IEventData, EventType) -> Unit) {
        PdfDocument(PdfReader(ByteArrayInputStream(pdfBytes))).use { pdf ->
            for (page in 1..pdf.numberOfPages) {
                val listener = object : IEventListener {
                    override fun eventOccurred(data: IEventData, type: EventType) {
                        if (type in events) {
                            (data as? PathRenderInfo)?.preserveGraphicsState()
                            (data as? TextRenderInfo)?.preserveGraphicsState()
                            onEvent(page, data, type)
                        }
                    }

                    override fun getSupportedEvents() = events
                }
                PdfCanvasProcessor(listener).processPageContent(pdf.getPage(page))
            }
        }
    }
}

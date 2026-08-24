// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.geom.Vector
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.EventType
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class ColumnsPdfRenderingTest {

    @Test
    fun `V3 first column text aligns with sibling text in the rendered PDF`() {
        val positions = renderTextBaselines(RenderingDefaults.V3)

        assertApproximately(
            expected = positions.getValue(SIBLING_TEXT),
            actual = positions.getValue(COLUMN_TEXT),
            message = "V3 first-column content should align with its sibling",
        )
    }

    @Test
    fun `legacy rendering defaults retain the historical two point column inset`() {
        for (defaults in listOf(RenderingDefaults.V1, RenderingDefaults.V2)) {
            val positions = renderTextBaselines(defaults)
            val inset = positions.getValue(COLUMN_TEXT) - positions.getValue(SIBLING_TEXT)

            assertApproximately(
                expected = 2f,
                actual = inset,
                message = "V${defaults.version} should retain its historical column inset",
            )
        }
    }

    private fun renderTextBaselines(defaults: RenderingDefaults): Map<String, Float> {
        val output = ByteArrayOutputStream()
        DirectPdfRenderer().render(
            document = columnsDocument(),
            data = emptyMap(),
            outputStream = output,
            renderingDefaults = defaults,
        )
        return extractHorizontalBaselines(output.toByteArray())
    }

    private fun columnsDocument(): TemplateDocument = TemplateDocument(
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "sibling" to textNode("sibling", SIBLING_TEXT),
            "columns" to Node(
                id = "columns",
                type = "columns",
                slots = listOf("column-0", "column-1"),
                props = mapOf("columnSizes" to listOf(1, 1), "gap" to 0),
            ),
            "column-text" to textNode("column-text", COLUMN_TEXT),
        ),
        slots = mapOf(
            "root-slot" to Slot("root-slot", "root", "children", listOf("sibling", "columns")),
            "column-0" to Slot("column-0", "columns", "column-0", listOf("column-text")),
            "column-1" to Slot("column-1", "columns", "column-1", emptyList()),
        ),
    )

    private fun textNode(id: String, text: String): Node = Node(
        id = id,
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

    private fun extractHorizontalBaselines(pdfBytes: ByteArray): Map<String, Float> {
        val positions = mutableMapOf<String, Float>()
        PdfReader(ByteArrayInputStream(pdfBytes)).use { reader ->
            PdfDocument(reader).use { pdf ->
                val processor = PdfCanvasProcessor(object : IEventListener {
                    override fun eventOccurred(data: IEventData?, type: EventType?) {
                        if (data !is TextRenderInfo) return
                        val text = data.text.trim()
                        if (text == SIBLING_TEXT || text == COLUMN_TEXT) {
                            positions.putIfAbsent(text, data.baseline.startPoint.get(Vector.I1))
                        }
                    }

                    override fun getSupportedEvents(): Set<EventType> = setOf(EventType.RENDER_TEXT)
                })
                processor.processPageContent(pdf.getPage(1))
            }
        }
        return positions
    }

    private fun assertApproximately(expected: Float, actual: Float, message: String) {
        assertTrue(
            abs(expected - actual) < 0.25f,
            "$message: expected $expected pt, got $actual pt",
        )
    }

    private companion object {
        const val SIBLING_TEXT = "SIBLING_ALIGNMENT_MARKER"
        const val COLUMN_TEXT = "COLUMN_ALIGNMENT_MARKER"
    }
}

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

/**
 * Verifies that `marginTop` declared on a text node actually translates
 * into vertical space between rendered text in the PDF — the visible
 * effect users expect.
 *
 * Existing tests only check `pdf.isNotEmpty()` for margin scenarios,
 * which left this regression silent for both `pt` and `sp` units.
 */
class TextNodeMarginRegressionTest {

    @Test
    fun `marginTop on second text node adds expected vertical gap (pt)`() {
        assertGapDeltaApprox(value = "10pt", expectedDeltaPt = 10f)
        assertGapDeltaApprox(value = "30pt", expectedDeltaPt = 30f)
    }

    @Test
    fun `marginTop on second text node adds expected vertical gap (sp)`() {
        assertGapDeltaApprox(value = "2sp", expectedDeltaPt = 8f)
        assertGapDeltaApprox(value = "5sp", expectedDeltaPt = 20f)
    }

    private fun assertGapDeltaApprox(value: String, expectedDeltaPt: Float) {
        val baselineGap = renderAndMeasureGap(secondTextMarginTop = null)
        val withMarginGap = renderAndMeasureGap(secondTextMarginTop = value)
        val delta = withMarginGap - baselineGap
        assertTrue(
            abs(delta - expectedDeltaPt) < 0.5f,
            "Expected marginTop=$value to add ~${expectedDeltaPt}pt to the inter-text gap, got delta=${delta}pt (baseline=$baselineGap, with=$withMarginGap)",
        )
    }

    private fun renderAndMeasureGap(secondTextMarginTop: String?): Float {
        val rootId = "root"
        val rootSlotId = "slot-root"
        val text1 = textNode("t1", "AAA", styles = null)
        val text2 = textNode("t2", "BBB", styles = secondTextMarginTop?.let { mapOf("marginTop" to it) })

        val document = TemplateDocument(
            root = rootId,
            nodes = mapOf(
                rootId to Node(id = rootId, type = "root", slots = listOf(rootSlotId)),
                text1.id to text1,
                text2.id to text2,
            ),
            slots = mapOf(
                rootSlotId to Slot(
                    id = rootSlotId,
                    nodeId = rootId,
                    name = "children",
                    children = listOf(text1.id, text2.id),
                ),
            ),
        )

        val out = ByteArrayOutputStream()
        DirectPdfRenderer().render(document, emptyMap(), out)

        val baselines = extractBaselines(out.toByteArray())
        return baselines["AAA"]!! - baselines["BBB"]!!
    }

    private fun textNode(id: String, text: String, styles: Map<String, Any?>?) = Node(
        id = id,
        type = "text",
        styles = styles,
        props = mapOf(
            "content" to mapOf(
                "type" to "doc",
                "content" to listOf(
                    mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to text))),
                ),
            ),
        ),
    )

    private fun extractBaselines(pdfBytes: ByteArray): Map<String, Float> {
        val baselines = mutableMapOf<String, Float>()
        PdfReader(ByteArrayInputStream(pdfBytes)).use { reader ->
            val pdf = PdfDocument(reader)
            val processor = PdfCanvasProcessor(object : IEventListener {
                override fun eventOccurred(data: IEventData?, type: EventType?) {
                    if (data is TextRenderInfo) {
                        val text = data.text.trim()
                        if (text.isNotEmpty()) {
                            val origin: Vector = data.baseline.startPoint
                            baselines.putIfAbsent(text, origin.get(Vector.I2))
                        }
                    }
                }
                override fun getSupportedEvents(): Set<EventType> = setOf(EventType.RENDER_TEXT)
            })
            processor.processPageContent(pdf.getPage(1))
            pdf.close()
        }
        return baselines
    }
}

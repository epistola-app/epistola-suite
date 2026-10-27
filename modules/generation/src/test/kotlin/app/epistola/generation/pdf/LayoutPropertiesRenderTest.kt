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
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * The layout properties of contract `2.0.0-alpha.3` (contract #97): a container's `minHeight`,
 * `verticalAlign` in table cells and columns, and per-column `columnStyles`, measured on the PDF.
 */
class LayoutPropertiesRenderTest {

    @Test
    fun `a container's min height pushes what follows it down, and grows with taller content`() {
        val without = gapBetweenContainers(minHeight = null)
        val with = gapBetweenContainers(minHeight = "100pt")
        assertTrue(with - without > 80f, "a 100pt min height adds room below a one-line container: $without -> $with")
        // Content taller than the min height is not clipped: the box grows past it.
        val tall = gapBetweenContainers(minHeight = "1pt")
        assertApprox(without, tall, "a min height below the content's height changes nothing")
    }

    @Test
    fun `a table cell's vertical align moves its content within a taller row`() {
        val top = cellBaseline(verticalAlign = null)
        val bottom = cellBaseline(verticalAlign = "bottom")
        val middle = cellBaseline(verticalAlign = "middle")
        assertTrue(top - bottom > 20f, "bottom sits lower than top: top $top, bottom $bottom")
        assertTrue(middle < top && middle > bottom, "middle sits between: top $top, middle $middle, bottom $bottom")
    }

    @Test
    fun `a column's styles align its content in a row of equal-height columns`() {
        val top = columnBaseline(columnStyles = null)
        val bottom = columnBaseline(columnStyles = listOf(emptyMap<String, Any>(), mapOf("verticalAlign" to "bottom")))
        // The short column is as tall as the long one beside it, so bottom-aligned text drops.
        assertTrue(top - bottom > 20f, "the short column fills the row's height: top $top, bottom $bottom")
    }

    private fun assertApprox(expected: Float, actual: Float, message: String) = assertTrue(abs(expected - actual) < 0.5f, "$message: expected ~$expected, got $actual")

    private fun gapBetweenContainers(minHeight: String?): Float {
        val nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-children")),
            "c1" to Node(id = "c1", type = "container", slots = listOf("c1-children"), styles = minHeight?.let { mapOf("minHeight" to it) }),
            "c2" to Node(id = "c2", type = "container", slots = listOf("c2-children")),
            "t1" to text("t1", "AAA"),
            "t2" to text("t2", "BBB"),
        )
        val slots = mapOf(
            "root-children" to Slot(id = "root-children", nodeId = "root", name = "children", children = listOf("c1", "c2")),
            "c1-children" to Slot(id = "c1-children", nodeId = "c1", name = "children", children = listOf("t1")),
            "c2-children" to Slot(id = "c2-children", nodeId = "c2", name = "children", children = listOf("t2")),
        )
        val baselines = baselines(render(TemplateDocument(root = "root", nodes = nodes, slots = slots)))
        return baselines.getValue("AAA") - baselines.getValue("BBB")
    }

    /** The baseline of a one-line cell beside a three-line cell in the same row. */
    private fun cellBaseline(verticalAlign: String?): Float {
        val nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-children")),
            "tbl" to Node(
                id = "tbl",
                type = "table",
                slots = listOf("cell-0-0", "cell-0-1"),
                props = buildMap {
                    put("rows", 1)
                    put("columns", 2)
                    verticalAlign?.let { put("cellStyles", mapOf("0-1" to mapOf("verticalAlign" to it))) }
                },
            ),
            "tall" to text("tall", "L1", "L2", "L3", "L4"),
            "short" to text("short", "SHORT"),
        )
        val slots = mapOf(
            "root-children" to Slot(id = "root-children", nodeId = "root", name = "children", children = listOf("tbl")),
            "cell-0-0" to Slot(id = "cell-0-0", nodeId = "tbl", name = "cell-0-0", children = listOf("tall")),
            "cell-0-1" to Slot(id = "cell-0-1", nodeId = "tbl", name = "cell-0-1", children = listOf("short")),
        )
        return baselines(render(TemplateDocument(root = "root", nodes = nodes, slots = slots))).getValue("SHORT")
    }

    /** The baseline of a one-line column beside a four-line column. */
    private fun columnBaseline(columnStyles: List<Map<String, Any>>?): Float {
        val nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-children")),
            "cols" to Node(
                id = "cols",
                type = "columns",
                slots = listOf("column-0", "column-1"),
                props = buildMap {
                    put("columnSizes", listOf(1, 1))
                    columnStyles?.let { put("columnStyles", it) }
                },
            ),
            "tall" to text("tall", "L1", "L2", "L3", "L4"),
            "short" to text("short", "SHORT"),
        )
        val slots = mapOf(
            "root-children" to Slot(id = "root-children", nodeId = "root", name = "children", children = listOf("cols")),
            "column-0" to Slot(id = "column-0", nodeId = "cols", name = "column-0", children = listOf("tall")),
            "column-1" to Slot(id = "column-1", nodeId = "cols", name = "column-1", children = listOf("short")),
        )
        return baselines(render(TemplateDocument(root = "root", nodes = nodes, slots = slots))).getValue("SHORT")
    }

    private fun text(id: String, vararg paragraphs: String) = Node(
        id = id,
        type = "text",
        props = mapOf(
            "content" to mapOf(
                "type" to "doc",
                "content" to paragraphs.map { mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to it))) },
            ),
        ),
    )

    private fun render(document: TemplateDocument): ByteArray {
        val out = ByteArrayOutputStream()
        DirectPdfRenderer().render(document, emptyMap(), out)
        return out.toByteArray()
    }

    private fun baselines(pdfBytes: ByteArray): Map<String, Float> {
        val found = mutableMapOf<String, Float>()
        PdfReader(ByteArrayInputStream(pdfBytes)).use { reader ->
            val pdf = PdfDocument(reader)
            PdfCanvasProcessor(
                object : IEventListener {
                    override fun eventOccurred(data: IEventData?, type: EventType?) {
                        if (data is TextRenderInfo && data.text.trim().isNotEmpty()) found.putIfAbsent(data.text.trim(), data.baseline.startPoint.get(Vector.I2))
                    }

                    override fun getSupportedEvents(): Set<EventType> = setOf(EventType.RENDER_TEXT)
                },
            ).processPageContent(pdf.getPage(1))
            pdf.close()
        }
        return found
    }
}

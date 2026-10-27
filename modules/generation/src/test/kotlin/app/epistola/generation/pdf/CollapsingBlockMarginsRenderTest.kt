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
 * Rendering defaults V8: a theme's block baseline collapses where a container's margin meets its
 * first or last child's, as CSS margins do, so nesting does not add the baseline twice. Measured
 * on the PDF as the distance between the baselines of two one-line texts.
 */
class CollapsingBlockMarginsRenderTest {

    private val baseline = ResolvedTheme(defaultBlockStyles = mapOf("marginBottom" to "20pt"))

    @Test
    fun `two containers are as far apart as two loose texts plus their own layout gap`() {
        val loose = gap(flat(), RenderingDefaults.V8)
        val nested = gap(containers(), RenderingDefaults.V8)
        val nestedWithout = gap(containers(), RenderingDefaults.V8, ResolvedTheme())
        // The 20pt baseline is added once between the two cards, not once per level.
        assertApprox(nestedWithout + 20f - 6f, nested, "the cards' baseline counts once (the 6pt fallback is replaced)")
        assertTrue(nested - loose < 12f, "nesting adds no second baseline: loose $loose, nested $nested")
    }

    @Test
    fun `under V7 the container and its text each add the baseline`() {
        val v7 = gap(containers(), RenderingDefaults.V7)
        val v8 = gap(containers(), RenderingDefaults.V8)
        assertTrue(v7 - v8 > 10f, "V7 keeps adding both margins, V8 collapses them: V7 $v7, V8 $v8")
    }

    @Test
    fun `V7 renders exactly as before V8 existed`() {
        // Measured on V7 before V8 was added: a release cut with V7 must keep these gaps.
        assertApprox(37.99f, gap(flat(), RenderingDefaults.V7), "two loose texts")
        assertApprox(37.99f, gap(oneContainer(), RenderingDefaults.V7), "two texts in one container")
        assertApprox(57.99f, gap(containers(), RenderingDefaults.V7), "two containers")
        assertApprox(23.99f, gap(containers(), RenderingDefaults.V7, ResolvedTheme()), "two containers, no baseline")
    }

    @Test
    fun `without a theme baseline V8 renders as V7`() {
        for (doc in listOf(flat(), oneContainer(), containers())) {
            assertApprox(gap(doc, RenderingDefaults.V7, ResolvedTheme()), gap(doc, RenderingDefaults.V8, ResolvedTheme()), "no baseline, nothing to collapse")
        }
    }

    @Test
    fun `texts inside one container keep the baseline between them`() {
        val loose = gap(flat(), RenderingDefaults.V8)
        val inOne = gap(oneContainer(), RenderingDefaults.V8)
        assertApprox(loose, inOne, "siblings keep their gap inside a container")
    }

    @Test
    fun `a container with bottom padding does not collapse`() {
        val collapsed = gap(containers(), RenderingDefaults.V8)
        val padded = gap(containers(firstStyles = mapOf("paddingBottom" to "0.1pt")), RenderingDefaults.V8)
        // With padding between them the child's 20pt and the container's 20pt both count again.
        assertTrue(padded - collapsed > 15f, "padding stops the collapse: collapsed $collapsed, padded $padded")
    }

    @Test
    fun `an empty container and a container of only a table render without error`() {
        val nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-children")),
            "empty" to Node(id = "empty", type = "container", slots = listOf("empty-children")),
            "c" to Node(id = "c", type = "container", slots = listOf("c-children")),
            "tbl" to Node(id = "tbl", type = "table", slots = listOf("cell-0-0"), props = mapOf("rows" to 1, "columns" to 1)),
            "t" to text("t", "IN-CELL"),
            "after" to text("after", "AFTER"),
        )
        val slots = mapOf(
            "root-children" to Slot(id = "root-children", nodeId = "root", name = "children", children = listOf("empty", "c", "after")),
            "empty-children" to Slot(id = "empty-children", nodeId = "empty", name = "children", children = emptyList()),
            "c-children" to Slot(id = "c-children", nodeId = "c", name = "children", children = listOf("tbl")),
            "cell-0-0" to Slot(id = "cell-0-0", nodeId = "tbl", name = "cell-0-0", children = listOf("t")),
        )
        val found = baselines(render(TemplateDocument(root = "root", nodes = nodes, slots = slots), RenderingDefaults.V8, baseline))
        assertTrue("IN-CELL" in found && "AFTER" in found, "both texts render: $found")
    }

    private fun gap(document: TemplateDocument, defaults: RenderingDefaults, theme: ResolvedTheme = baseline): Float {
        val found = baselines(render(document, defaults, theme))
        return found.getValue("AAA") - found.getValue("BBB")
    }

    private fun flat(): TemplateDocument = document(
        mapOf("t1" to text("t1", "AAA"), "t2" to text("t2", "BBB")),
        mapOf("root-children" to listOf("t1", "t2")),
    )

    private fun oneContainer(): TemplateDocument = document(
        mapOf(
            "c" to Node(id = "c", type = "container", slots = listOf("c-children")),
            "t1" to text("t1", "AAA"),
            "t2" to text("t2", "BBB"),
        ),
        mapOf("root-children" to listOf("c"), "c-children" to listOf("t1", "t2")),
    )

    private fun containers(firstStyles: Map<String, Any>? = null): TemplateDocument = document(
        mapOf(
            "c1" to Node(id = "c1", type = "container", slots = listOf("c1-children"), styles = firstStyles),
            "c2" to Node(id = "c2", type = "container", slots = listOf("c2-children")),
            "t1" to text("t1", "AAA"),
            "t2" to text("t2", "BBB"),
        ),
        mapOf("root-children" to listOf("c1", "c2"), "c1-children" to listOf("t1"), "c2-children" to listOf("t2")),
    )

    private fun document(nodes: Map<String, Node>, children: Map<String, List<String>>): TemplateDocument {
        val all = nodes + ("root" to Node(id = "root", type = "root", slots = listOf("root-children")))
        val slots = children.mapValues { (slotId, ids) ->
            val owner = slotId.removeSuffix("-children")
            Slot(id = slotId, nodeId = owner, name = "children", children = ids)
        }
        return TemplateDocument(root = "root", nodes = all, slots = slots)
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

    private fun render(document: TemplateDocument, defaults: RenderingDefaults, theme: ResolvedTheme): ByteArray {
        val out = ByteArrayOutputStream()
        DirectPdfRenderer().render(document, emptyMap(), out, resolvedTheme = theme, renderingDefaults = defaults)
        return out.toByteArray()
    }

    private fun assertApprox(expected: Float, actual: Float, message: String) = assertTrue(abs(expected - actual) < 1f, "$message: expected ~$expected, got $actual")

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

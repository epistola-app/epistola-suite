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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rendering defaults V7 take a block's vertical rhythm from the theme's `defaultBlockStyles`
 * (contract `2.0.0-alpha.3`), and without one keep 6pt below a flow block whatever the spacing unit.
 * V6, which every release cut before V7 renders with, keeps its own `1.5sp` and ignores the theme's.
 */
class ThemeBlockSpacingTest {

    @Test
    fun `V6 renders as it always did, with its own spacing scaled by the unit and no theme baseline`() {
        val v6 = RenderingDefaults.forVersion(6)
        val atDefaultUnit = gap(v6, ResolvedTheme())
        val atLargeUnit = gap(v6, ResolvedTheme(spacingUnit = 16f))
        assertApprox(18f, atLargeUnit - atDefaultUnit, "1.5sp grows from 6pt to 24pt with a 16pt unit")
        assertApprox(atLargeUnit, gap(v6, ResolvedTheme(spacingUnit = 16f, defaultBlockStyles = mapOf("marginBottom" to "20pt"))), "a theme baseline changes nothing in V6")
    }

    @Test
    fun `V7 keeps 6pt below a block without a theme baseline, whatever the spacing unit`() {
        val v7 = RenderingDefaults.forVersion(7)
        assertApprox(gap(v7, ResolvedTheme()), gap(v7, ResolvedTheme(spacingUnit = 16f)), "the fallback is in points")
        assertApprox(gap(RenderingDefaults.forVersion(6), ResolvedTheme()), gap(v7, ResolvedTheme()), "at the default unit, V7 looks like V6")
    }

    @Test
    fun `V7 takes the theme's baseline margins for a block`() {
        val v7 = RenderingDefaults.forVersion(7)
        val fallback = gap(v7, ResolvedTheme())
        // Every block takes it, the container (20pt for 6pt) and the text inside it (20pt for none).
        assertApprox(34f, gap(v7, ResolvedTheme(defaultBlockStyles = mapOf("marginBottom" to "20pt"))) - fallback, "20pt on the container and on its text")
        assertApprox(34f, gap(v7, ResolvedTheme(spacingUnit = 4f, defaultBlockStyles = mapOf("marginBottom" to "5sp"))) - fallback, "a baseline in sp scales with the theme's unit")
    }

    @Test
    fun `V7 is the version new releases are cut with`() {
        assertEquals(7, RenderingDefaults.CURRENT.version)
        assertTrue(RenderingDefaults.CURRENT.themeBlockSpacing)
    }

    private fun assertApprox(expected: Float, actual: Float, message: String) = assertTrue(abs(expected - actual) < 0.5f, "$message: expected ~$expected, got $actual")

    /** The vertical distance between the text of two containers, one after the other. */
    private fun gap(defaults: RenderingDefaults, theme: ResolvedTheme): Float {
        val document = TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("root-children")),
                "c1" to Node(id = "c1", type = "container", slots = listOf("c1-children")),
                "c2" to Node(id = "c2", type = "container", slots = listOf("c2-children")),
                "t1" to text("t1", "AAA"),
                "t2" to text("t2", "BBB"),
            ),
            slots = mapOf(
                "root-children" to Slot(id = "root-children", nodeId = "root", name = "children", children = listOf("c1", "c2")),
                "c1-children" to Slot(id = "c1-children", nodeId = "c1", name = "children", children = listOf("t1")),
                "c2-children" to Slot(id = "c2-children", nodeId = "c2", name = "children", children = listOf("t2")),
            ),
        )
        val out = ByteArrayOutputStream()
        DirectPdfRenderer().render(document, emptyMap(), out, resolvedTheme = theme, renderingDefaults = defaults)
        val baselines = baselines(out.toByteArray())
        return baselines.getValue("AAA") - baselines.getValue("BBB")
    }

    private fun text(id: String, value: String) = Node(
        id = id,
        type = "text",
        props = mapOf("content" to mapOf("type" to "doc", "content" to listOf(mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to value)))))),
    )

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

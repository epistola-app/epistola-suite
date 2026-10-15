// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.EventType
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData
import com.itextpdf.kernel.pdf.canvas.parser.data.PathRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `padding`, `margin` and `border` shorthands the style registry offers, which the renderer
 * ignored. From rendering defaults V6 they take effect, inline and in a theme preset; earlier
 * versions render as they did.
 */
class StyleShorthandsRenderTest {

    private val red = listOf(1f, 0f, 0f)

    /** A container holding one line of text, styled inline or through the preset `card`. */
    private fun boxed(inlineStyles: Map<String, Any>? = null, preset: String? = null): TemplateDocument {
        val doc = BandDoc()
        val base = doc.build(doc.container("box", doc.text("boxed", "BOXED")), doc.text("after", "AFTER"))
        val box = base.nodes.getValue("box").copy(styles = inlineStyles, stylePreset = preset)
        return base.copy(nodes = base.nodes + ("box" to box))
    }

    private val cardStyles = mapOf("padding" to "20pt", "border" to "1pt solid #ff0000")

    private fun render(document: TemplateDocument, defaults: RenderingDefaults, presets: Map<String, Map<String, Any>> = emptyMap()): ByteArray = ByteArrayOutputStream().also {
        DirectPdfRenderer().render(document, emptyMap(), it, resolvedTheme = ResolvedTheme(blockStylePresets = presets), renderingDefaults = defaults)
    }.toByteArray()

    private fun ByteArray.xOf(text: String): Float = drawnText(this).getValue(1).first { it.text.startsWith(text) }.x

    @Test
    fun `inline padding and border shorthands pad the content and draw the border`() {
        val unstyled = render(boxed(), RenderingDefaults.V6)
        val styled = render(boxed(inlineStyles = cardStyles), RenderingDefaults.V6)

        assertEquals(unstyled.xOf("BOXED") + 21f, styled.xOf("BOXED"), "20pt padding plus the 1pt border")
        assertTrue(red in borderColours(styled), "the red border must be drawn")
    }

    @Test
    fun `shorthands in a theme preset take effect`() {
        val unstyled = render(boxed(), RenderingDefaults.V6)
        val styled = render(boxed(preset = "card"), RenderingDefaults.V6, presets = mapOf("card" to cardStyles))

        assertEquals(unstyled.xOf("BOXED") + 21f, styled.xOf("BOXED"))
        assertTrue(red in borderColours(styled))
    }

    @Test
    fun `a margin shorthand moves the block`() {
        val unstyled = render(boxed(), RenderingDefaults.V6)
        val styled = render(boxed(inlineStyles = mapOf("margin" to "0pt 0pt 0pt 30pt")), RenderingDefaults.V6)

        assertEquals(unstyled.xOf("BOXED") + 30f, styled.xOf("BOXED"))
    }

    @Test
    fun `an explicit side overrides the shorthand`() {
        val unstyled = render(boxed(), RenderingDefaults.V6)
        val styled = render(boxed(inlineStyles = mapOf("padding" to "20pt", "paddingLeft" to "5pt")), RenderingDefaults.V6)

        assertEquals(unstyled.xOf("BOXED") + 5f, styled.xOf("BOXED"))
    }

    @Test
    fun `before V6 the shorthands are ignored`() {
        val unstyled = render(boxed(), RenderingDefaults.V5)
        val styled = render(boxed(inlineStyles = cardStyles), RenderingDefaults.V5)

        assertEquals(unstyled.xOf("BOXED"), styled.xOf("BOXED"))
        assertFalse(red in borderColours(styled))
    }

    /** RGB colours of every stroked or filled path, rounded to two decimals. */
    private fun borderColours(pdfBytes: ByteArray): Set<List<Float>> {
        val colours = mutableSetOf<List<Float>>()
        PdfDocument(PdfReader(ByteArrayInputStream(pdfBytes))).use { pdf ->
            val listener = object : IEventListener {
                override fun eventOccurred(data: IEventData, type: EventType) {
                    val info = data as? PathRenderInfo ?: return
                    info.preserveGraphicsState()
                    listOfNotNull(
                        info.strokeColor.takeIf { info.operation and PathRenderInfo.STROKE != 0 },
                        info.fillColor.takeIf { info.operation and PathRenderInfo.FILL != 0 },
                    ).forEach { colour ->
                        (colour as? DeviceRgb)?.colorValue?.let { rgb -> colours += rgb.map { Math.round(it * 100f) / 100f } }
                    }
                }

                override fun getSupportedEvents() = setOf(EventType.RENDER_PATH)
            }
            for (page in 1..pdf.numberOfPages) PdfCanvasProcessor(listener).processPageContent(pdf.getPage(page))
        }
        return colours
    }
}

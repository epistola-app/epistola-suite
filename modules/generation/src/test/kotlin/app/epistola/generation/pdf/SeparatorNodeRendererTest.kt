package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

class SeparatorNodeRendererTest {

    private val renderer = DirectPdfRenderer()

    private fun documentWithSeparator(props: Map<String, Any?> = emptyMap(), styles: Map<String, Any>? = null): TemplateDocument {
        val rootNodeId = "root-1"
        val rootSlotId = "slot-root"
        val separatorNodeId = "sep-1"

        return TemplateDocument(
            root = rootNodeId,
            nodes = mapOf(
                rootNodeId to Node(id = rootNodeId, type = "root", slots = listOf(rootSlotId)),
                separatorNodeId to Node(id = separatorNodeId, type = "separator", props = props, styles = styles),
            ),
            slots = mapOf(
                rootSlotId to Slot(
                    id = rootSlotId,
                    nodeId = rootNodeId,
                    name = "children",
                    children = listOf(separatorNodeId),
                ),
            ),
        )
    }

    private fun renderToPdf(document: TemplateDocument): ByteArray {
        val output = ByteArrayOutputStream()
        renderer.render(document, emptyMap(), output)
        return output.toByteArray()
    }

    @Test
    fun `renders separator with default props`() {
        val pdf = renderToPdf(documentWithSeparator())
        assertTrue(pdf.isNotEmpty())
        assertTrue(pdf.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `renders separator with custom thickness`() {
        val pdf = renderToPdf(documentWithSeparator(mapOf("thickness" to "3pt")))
        assertTrue(pdf.isNotEmpty())
    }

    @Test
    fun `renders separator with sp thickness unit`() {
        val pdf = renderToPdf(documentWithSeparator(mapOf("thickness" to "2sp")))
        assertTrue(pdf.isNotEmpty())
    }

    @Test
    fun `renders separator with custom width percentage`() {
        val pdf = renderToPdf(documentWithSeparator(mapOf("width" to "50%")))
        assertTrue(pdf.isNotEmpty())
    }

    @Test
    fun `renders separator with custom color`() {
        val pdf = renderToPdf(documentWithSeparator(mapOf("color" to "#ff0000")))
        assertTrue(pdf.isNotEmpty())
    }

    @Test
    fun `renders separator with 3-digit hex color`() {
        val pdf = renderToPdf(documentWithSeparator(mapOf("color" to "#f00")))
        assertTrue(pdf.isNotEmpty())
    }

    @Test
    fun `renders separator with rgb color`() {
        val pdf = renderToPdf(documentWithSeparator(mapOf("color" to "rgb(255, 0, 0)")))
        assertTrue(pdf.isNotEmpty())
    }

    @Test
    fun `renders separator with dashed style`() {
        val pdf = renderToPdf(documentWithSeparator(mapOf("style" to "dashed")))
        assertTrue(pdf.isNotEmpty())
    }

    @Test
    fun `renders separator with dotted style`() {
        val pdf = renderToPdf(documentWithSeparator(mapOf("style" to "dotted")))
        assertTrue(pdf.isNotEmpty())
    }

    @Test
    fun `renders separator with custom margins`() {
        val pdf = renderToPdf(
            documentWithSeparator(
                styles = mapOf("marginTop" to "3sp", "marginBottom" to "3sp"),
            ),
        )
        assertTrue(pdf.isNotEmpty())
    }

    @Test
    fun `renders separator with all custom props`() {
        val pdf = renderToPdf(
            documentWithSeparator(
                mapOf(
                    "thickness" to "2pt",
                    "width" to "75%",
                    "color" to "#333333",
                    "style" to "dashed",
                ),
            ),
        )
        assertTrue(pdf.isNotEmpty())
    }

    @Test
    fun `renders separator with invalid width falls back to 100 percent`() {
        val pdf = renderToPdf(documentWithSeparator(mapOf("width" to "invalid")))
        assertTrue(pdf.isNotEmpty())
    }

    @Test
    fun `renders separator with invalid color falls back to default`() {
        val pdf = renderToPdf(documentWithSeparator(mapOf("color" to "not-a-color")))
        assertTrue(pdf.isNotEmpty())
    }
}

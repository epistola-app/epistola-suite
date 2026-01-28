package app.epistola.generation.pdf

import app.epistola.template.model.Margins
import app.epistola.template.model.Orientation
import app.epistola.template.model.PageBreakBlock
import app.epistola.template.model.PageFormat
import app.epistola.template.model.PageSettings
import app.epistola.template.model.TemplateModel
import app.epistola.template.model.TextBlock
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

class DirectPdfRendererTest {
    private val renderer = DirectPdfRenderer()

    @Test
    fun `renders empty template`() {
        val template = TemplateModel(
            id = "test",
            name = "Test Template",
            pageSettings = PageSettings(),
            blocks = emptyList(),
        )

        val output = ByteArrayOutputStream()
        renderer.render(template, emptyMap(), output)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        // PDF files start with %PDF-
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `renders template with text block`() {
        val template = TemplateModel(
            id = "test",
            name = "Test Template",
            pageSettings = PageSettings(),
            blocks = listOf(
                TextBlock(
                    id = "text1",
                    content = mapOf(
                        "type" to "doc",
                        "content" to listOf(
                            mapOf(
                                "type" to "paragraph",
                                "content" to listOf(
                                    mapOf("type" to "text", "text" to "Hello World"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val output = ByteArrayOutputStream()
        renderer.render(template, emptyMap(), output)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `renders template with expression`() {
        val template = TemplateModel(
            id = "test",
            name = "Test Template",
            pageSettings = PageSettings(),
            blocks = listOf(
                TextBlock(
                    id = "text1",
                    content = mapOf(
                        "type" to "doc",
                        "content" to listOf(
                            mapOf(
                                "type" to "paragraph",
                                "content" to listOf(
                                    mapOf("type" to "text", "text" to "Hello {{name}}!"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val data = mapOf("name" to "John")
        val output = ByteArrayOutputStream()
        renderer.render(template, data, output)

        val pdfBytes = output.toByteArray()
//        File("/tmp/test.pdf").writeBytes(pdfBytes)
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `renders with different page settings`() {
        val template = TemplateModel(
            id = "test",
            name = "Test Template",
            pageSettings = PageSettings(
                format = PageFormat.Letter,
                orientation = Orientation.Landscape,
                margins = Margins(top = 30, right = 25, bottom = 30, left = 25),
            ),
            blocks = emptyList(),
        )

        val output = ByteArrayOutputStream()
        renderer.render(template, emptyMap(), output)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
    }

    @Test
    fun `renders template with page break`() {
        val template = TemplateModel(
            id = "test",
            name = "Test Template",
            pageSettings = PageSettings(),
            blocks = listOf(
                TextBlock(
                    id = "text1",
                    content = mapOf(
                        "type" to "doc",
                        "content" to listOf(
                            mapOf(
                                "type" to "paragraph",
                                "content" to listOf(
                                    mapOf("type" to "text", "text" to "Page 1"),
                                ),
                            ),
                        ),
                    ),
                ),
                PageBreakBlock(id = "pagebreak1"),
                TextBlock(
                    id = "text2",
                    content = mapOf(
                        "type" to "doc",
                        "content" to listOf(
                            mapOf(
                                "type" to "paragraph",
                                "content" to listOf(
                                    mapOf("type" to "text", "text" to "Page 2"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val output = ByteArrayOutputStream()
        renderer.render(template, emptyMap(), output)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }
}

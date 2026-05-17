package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression for the defect where rich-text bold/italic marks and headings
 * rendered with the built-in Liberation/Helvetica instead of the selected
 * font family. They must now resolve through the family
 * ([FontFamilyResolver]) at the effective weight/italic.
 */
class RichTextFontFamilyTest {

    private val liberationRegular: ByteArray =
        RichTextFontFamilyTest::class.java.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")!!.readBytes()

    private fun textNodeDoc() = mapOf(
        "type" to "doc",
        "content" to listOf(
            mapOf(
                "type" to "heading",
                "attrs" to mapOf("level" to 1),
                "content" to listOf(mapOf("type" to "text", "text" to "A Heading")),
            ),
            mapOf(
                "type" to "paragraph",
                "content" to listOf(
                    mapOf("type" to "text", "text" to "plain and "),
                    mapOf(
                        "type" to "text",
                        "text" to "bold",
                        "marks" to listOf(mapOf("type" to "bold")),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `headings and bold marks resolve through the selected family, not the built-in font`() {
        val calls = ConcurrentHashMap.newKeySet<String>()
        val resolver = FontFamilyResolver { _, slug, weight, italic ->
            calls.add("$slug|$weight|$italic")
            liberationRegular
        }

        val textNode = Node(
            id = "text1",
            type = "text",
            props = mapOf("content" to textNodeDoc()),
        )
        val rootNode = Node(id = "root-1", type = "root", slots = listOf("slot-root"))
        val rootSlot = Slot(id = "slot-root", nodeId = "root-1", name = "children", children = listOf("text1"))
        val document = TemplateDocument(
            root = "root-1",
            nodes = mapOf("root-1" to rootNode, "text1" to textNode),
            slots = mapOf("slot-root" to rootSlot),
        )

        val output = ByteArrayOutputStream()
        DirectPdfRenderer().render(
            document = document,
            data = emptyMap(),
            outputStream = output,
            resolvedTheme = ResolvedTheme(
                documentStyles = mapOf("fontFamily" to mapOf("slug" to "inter", "catalogKey" to "system")),
            ),
            fontFamilyResolver = resolver,
        )

        assertTrue(output.toByteArray().decodeToString(0, 5).startsWith("%PDF"))
        // The heading is bold-by-default → family resolved at weight >= 700.
        assertTrue(calls.any { it == "inter|700|false" }, "Heading must resolve the family bold face; calls=$calls")
        // The bold-marked run → family resolved at weight 700.
        assertTrue(
            calls.count { it.startsWith("inter|700|false") } >= 1,
            "Bold mark must resolve through the family, not the built-in bold; calls=$calls",
        )
        // Plain text resolves the family at the base weight (container path).
        assertTrue(calls.any { it == "inter|400|false" }, "Plain text must resolve the family regular face; calls=$calls")
    }
}

package app.epistola.generation

import app.epistola.generation.expression.CompositeExpressionEvaluator
import app.epistola.generation.expression.JsonataEvaluator
import app.epistola.generation.pdf.FontCache
import com.itextpdf.layout.element.List
import com.itextpdf.layout.element.Paragraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TipTapConverterTest {
    private val expressionEvaluator = CompositeExpressionEvaluator(
        jsonataEvaluator = JsonataEvaluator(),
    )
    private val converter = TipTapConverter(expressionEvaluator)
    private val fontCache = FontCache()

    @Test
    fun `converts null content to empty list`() {
        val result = converter.convert(null, emptyMap(), fontCache = fontCache)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `converts empty content to empty list`() {
        val content = mapOf("content" to emptyList<Map<String, Any>>())
        val result = converter.convert(content, emptyMap(), fontCache = fontCache)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `converts simple paragraph`() {
        val content = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf(
                    "type" to "paragraph",
                    "content" to listOf(
                        mapOf("type" to "text", "text" to "Hello World"),
                    ),
                ),
            ),
        )

        val result = converter.convert(content, emptyMap(), fontCache = fontCache)

        assertEquals(1, result.size)
        assertTrue(result[0] is Paragraph)
    }

    @Test
    fun `converts paragraph with expression`() {
        val content = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf(
                    "type" to "paragraph",
                    "content" to listOf(
                        mapOf("type" to "text", "text" to "Hello {{name}}!"),
                    ),
                ),
            ),
        )

        val data = mapOf("name" to "John")
        val result = converter.convert(content, data, fontCache = fontCache)

        assertEquals(1, result.size)
        assertTrue(result[0] is Paragraph)
    }

    @Test
    fun `converts heading level 1`() {
        val content = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf(
                    "type" to "heading",
                    "attrs" to mapOf("level" to 1),
                    "content" to listOf(
                        mapOf("type" to "text", "text" to "Main Title"),
                    ),
                ),
            ),
        )

        val result = converter.convert(content, emptyMap(), fontCache = fontCache)

        assertEquals(1, result.size)
        assertTrue(result[0] is Paragraph)
    }

    @Test
    fun `converts heading level 2`() {
        val content = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf(
                    "type" to "heading",
                    "attrs" to mapOf("level" to 2),
                    "content" to listOf(
                        mapOf("type" to "text", "text" to "Section Title"),
                    ),
                ),
            ),
        )

        val result = converter.convert(content, emptyMap(), fontCache = fontCache)

        assertEquals(1, result.size)
        assertTrue(result[0] is Paragraph)
    }

    @Test
    fun `converts heading level 3`() {
        val content = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf(
                    "type" to "heading",
                    "attrs" to mapOf("level" to 3),
                    "content" to listOf(
                        mapOf("type" to "text", "text" to "Subsection"),
                    ),
                ),
            ),
        )

        val result = converter.convert(content, emptyMap(), fontCache = fontCache)

        assertEquals(1, result.size)
    }

    @Test
    fun `converts heading with default level when attrs missing`() {
        val content = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf(
                    "type" to "heading",
                    "content" to listOf(
                        mapOf("type" to "text", "text" to "No Level Heading"),
                    ),
                ),
            ),
        )

        val result = converter.convert(content, emptyMap(), fontCache = fontCache)

        assertEquals(1, result.size)
    }

    @Test
    fun `converts bullet list`() {
        val content = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf(
                    "type" to "bulletList",
                    "content" to listOf(
                        mapOf(
                            "type" to "listItem",
                            "content" to listOf(
                                mapOf(
                                    "type" to "paragraph",
                                    "content" to listOf(
                                        mapOf("type" to "text", "text" to "Item 1"),
                                    ),
                                ),
                            ),
                        ),
                        mapOf(
                            "type" to "listItem",
                            "content" to listOf(
                                mapOf(
                                    "type" to "paragraph",
                                    "content" to listOf(
                                        mapOf("type" to "text", "text" to "Item 2"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = converter.convert(content, emptyMap(), fontCache = fontCache)

        assertEquals(1, result.size)
        assertTrue(result[0] is List)
    }

    @Test
    fun `converts ordered list`() {
        val content = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf(
                    "type" to "orderedList",
                    "content" to listOf(
                        mapOf(
                            "type" to "listItem",
                            "content" to listOf(
                                mapOf(
                                    "type" to "paragraph",
                                    "content" to listOf(
                                        mapOf("type" to "text", "text" to "First"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = converter.convert(content, emptyMap(), fontCache = fontCache)

        assertEquals(1, result.size)
        assertTrue(result[0] is List)
    }

    @Test
    fun `converts text with bold mark`() {
        val content = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf(
                    "type" to "paragraph",
                    "content" to listOf(
                        mapOf(
                            "type" to "text",
                            "text" to "Bold text",
                            "marks" to listOf(mapOf("type" to "bold")),
                        ),
                    ),
                ),
            ),
        )

        val result = converter.convert(content, emptyMap(), fontCache = fontCache)

        assertEquals(1, result.size)
    }

    @Test
    fun `converts text with italic mark`() {
        val content = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf(
                    "type" to "paragraph",
                    "content" to listOf(
                        mapOf(
                            "type" to "text",
                            "text" to "Italic text",
                            "marks" to listOf(mapOf("type" to "italic")),
                        ),
                    ),
                ),
            ),
        )

        val result = converter.convert(content, emptyMap(), fontCache = fontCache)

        assertEquals(1, result.size)
    }

    @Test
    fun `converts text with bold and italic marks`() {
        val content = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf(
                    "type" to "paragraph",
                    "content" to listOf(
                        mapOf(
                            "type" to "text",
                            "text" to "Bold Italic text",
                            "marks" to listOf(
                                mapOf("type" to "bold"),
                                mapOf("type" to "italic"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = converter.convert(content, emptyMap(), fontCache = fontCache)

        assertEquals(1, result.size)
    }

    @Test
    fun `converts text with underline mark`() {
        val content = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf(
                    "type" to "paragraph",
                    "content" to listOf(
                        mapOf(
                            "type" to "text",
                            "text" to "Underlined text",
                            "marks" to listOf(mapOf("type" to "underline")),
                        ),
                    ),
                ),
            ),
        )

        val result = converter.convert(content, emptyMap(), fontCache = fontCache)

        assertEquals(1, result.size)
    }

    @Test
    fun `converts text with strike mark`() {
        val content = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf(
                    "type" to "paragraph",
                    "content" to listOf(
                        mapOf(
                            "type" to "text",
                            "text" to "Strikethrough text",
                            "marks" to listOf(mapOf("type" to "strike")),
                        ),
                    ),
                ),
            ),
        )

        val result = converter.convert(content, emptyMap(), fontCache = fontCache)

        assertEquals(1, result.size)
    }

    @Test
    fun `converts text with color style using 6-digit hex`() {
        val content = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf(
                    "type" to "paragraph",
                    "content" to listOf(
                        mapOf(
                            "type" to "text",
                            "text" to "Red text",
                            "marks" to listOf(
                                mapOf(
                                    "type" to "textStyle",
                                    "attrs" to mapOf("color" to "#FF0000"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = converter.convert(content, emptyMap(), fontCache = fontCache)

        assertEquals(1, result.size)
    }

    @Test
    fun `converts text with color style using 3-digit hex`() {
        val content = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf(
                    "type" to "paragraph",
                    "content" to listOf(
                        mapOf(
                            "type" to "text",
                            "text" to "Green text",
                            "marks" to listOf(
                                mapOf(
                                    "type" to "textStyle",
                                    "attrs" to mapOf("color" to "#0F0"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = converter.convert(content, emptyMap(), fontCache = fontCache)

        assertEquals(1, result.size)
    }

    @Test
    fun `handles invalid color gracefully`() {
        val content = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf(
                    "type" to "paragraph",
                    "content" to listOf(
                        mapOf(
                            "type" to "text",
                            "text" to "Invalid color",
                            "marks" to listOf(
                                mapOf(
                                    "type" to "textStyle",
                                    "attrs" to mapOf("color" to "invalid"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = converter.convert(content, emptyMap(), fontCache = fontCache)

        assertEquals(1, result.size)
    }

    @Test
    fun `converts expression node`() {
        val content = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf(
                    "type" to "paragraph",
                    "content" to listOf(
                        mapOf("type" to "text", "text" to "Hello "),
                        mapOf(
                            "type" to "expression",
                            "attrs" to mapOf("expression" to "name"),
                        ),
                        mapOf("type" to "text", "text" to "!"),
                    ),
                ),
            ),
        )

        val data = mapOf("name" to "World")
        val result = converter.convert(content, data, fontCache = fontCache)

        assertEquals(1, result.size)
    }

    @Test
    fun `converts expression node with javascript language`() {
        val content = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf(
                    "type" to "paragraph",
                    "content" to listOf(
                        mapOf(
                            "type" to "expression",
                            "attrs" to mapOf(
                                "expression" to "name",
                                "language" to "javascript",
                            ),
                        ),
                    ),
                ),
            ),
        )

        val data = mapOf("name" to "Test")
        val result = converter.convert(content, data, fontCache = fontCache)

        assertEquals(1, result.size)
    }

    @Test
    fun `uses loop context for expressions`() {
        val content = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf(
                    "type" to "paragraph",
                    "content" to listOf(
                        mapOf(
                            "type" to "expression",
                            "attrs" to mapOf("expression" to "item.name"),
                        ),
                    ),
                ),
            ),
        )

        val data = emptyMap<String, Any?>()
        val loopContext = mapOf("item" to mapOf("name" to "Loop Item"))
        val result = converter.convert(content, data, loopContext, fontCache)

        assertEquals(1, result.size)
    }

    @Test
    fun `ignores unknown node types`() {
        val content = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf("type" to "unknown", "content" to emptyList<Any>()),
                mapOf(
                    "type" to "paragraph",
                    "content" to listOf(
                        mapOf("type" to "text", "text" to "Valid"),
                    ),
                ),
            ),
        )

        val result = converter.convert(content, emptyMap(), fontCache = fontCache)

        assertEquals(1, result.size) // Only the paragraph is converted
    }

    @Test
    fun `handles empty list items`() {
        val content = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf(
                    "type" to "bulletList",
                    "content" to listOf(
                        mapOf(
                            "type" to "listItem",
                            "content" to emptyList<Any>(),
                        ),
                    ),
                ),
            ),
        )

        val result = converter.convert(content, emptyMap(), fontCache = fontCache)

        assertEquals(1, result.size)
        assertTrue(result[0] is List)
    }

    @Test
    fun `handles paragraph without content`() {
        val content = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf("type" to "paragraph"),
            ),
        )

        val result = converter.convert(content, emptyMap(), fontCache = fontCache)

        assertEquals(1, result.size)
        assertTrue(result[0] is Paragraph)
    }

    @Test
    fun `handles heading without content`() {
        val content = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf(
                    "type" to "heading",
                    "attrs" to mapOf("level" to 1),
                ),
            ),
        )

        val result = converter.convert(content, emptyMap(), fontCache = fontCache)

        assertEquals(1, result.size)
    }
}

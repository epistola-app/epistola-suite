package app.epistola.generation.pdf

import app.epistola.generation.TipTapConverter
import app.epistola.generation.expression.CompositeExpressionEvaluator
import app.epistola.template.model.ConditionalBlock
import app.epistola.template.model.Expression
import app.epistola.template.model.ExpressionLanguage
import app.epistola.template.model.LoopBlock
import app.epistola.template.model.TableBlock
import app.epistola.template.model.TableCell
import app.epistola.template.model.TableRow
import app.epistola.template.model.TextBlock
import app.epistola.template.model.ContainerBlock
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.IElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RendererStyleInheritanceTest {
    private class CapturingRenderer : BlockRenderer {
        val contexts = mutableListOf<RenderContext>()

        override fun render(
            block: app.epistola.template.model.Block,
            context: RenderContext,
            blockRenderers: BlockRendererRegistry,
        ): List<IElement> {
            contexts.add(context)
            return listOf(Div())
        }
    }

    private fun createContext(
        data: Map<String, Any?> = emptyMap(),
        inheritedStyles: Map<String, Any> = emptyMap(),
    ): RenderContext {
        val evaluator = CompositeExpressionEvaluator()
        return RenderContext(
            data = data,
            loopContext = emptyMap(),
            documentStyles = null,
            expressionEvaluator = evaluator,
            tipTapConverter = TipTapConverter(evaluator, ExpressionLanguage.Jsonata),
            defaultExpressionLanguage = ExpressionLanguage.Jsonata,
            fontCache = FontCache(),
            blockStylePresets = emptyMap(),
            inheritedStyles = inheritedStyles,
        )
    }

    @Test
    fun `container renderer passes inherited styles to child rendering`() {
        val capture = CapturingRenderer()
        val registry = BlockRendererRegistry(
            mapOf(
                "container" to ContainerBlockRenderer(),
                "text" to capture,
            ),
        )

        val container = ContainerBlock(
            id = "container-1",
            styles = mapOf(
                "fontSize" to "2rem",
                "backgroundColor" to "#ffeecc",
            ),
            children = listOf(TextBlock(id = "text-1")),
        )

        registry.render(
            container,
            createContext(inheritedStyles = mapOf("fontSize" to "4rem", "color" to "#333333")),
        )

        val childContext = capture.contexts.single()
        assertEquals("2rem", childContext.inheritedStyles["fontSize"])
        assertEquals("#333333", childContext.inheritedStyles["color"])
        assertEquals("#ffeecc", childContext.inheritedStyles["backgroundColor"])
    }

    @Test
    fun `table renderer applies table and cell inheritance to nested block`() {
        val capture = CapturingRenderer()
        val registry = BlockRendererRegistry(
            mapOf(
                "table" to TableBlockRenderer(),
                "text" to capture,
            ),
        )

        val table = TableBlock(
            id = "table-1",
            styles = mapOf(
                "fontSize" to "2rem",
                "backgroundColor" to "#ffeecc",
                "paddingTop" to "12px",
            ),
            rows = listOf(
                TableRow(
                    id = "row-1",
                    cells = listOf(
                        TableCell(
                            id = "cell-1",
                            styles = mapOf("color" to "#111111"),
                            children = listOf(TextBlock(id = "text-1")),
                        ),
                    ),
                ),
            ),
        )

        registry.render(
            table,
            createContext(inheritedStyles = mapOf("fontSize" to "4rem", "color" to "#333333")),
        )

        val childContext = capture.contexts.single()
        assertEquals("2rem", childContext.inheritedStyles["fontSize"])
        assertEquals("#111111", childContext.inheritedStyles["color"])
        assertEquals("#ffeecc", childContext.inheritedStyles["backgroundColor"])
        assertNull(childContext.inheritedStyles["paddingTop"])
    }

    @Test
    fun `conditional renderer forwards inherited styles when condition is true`() {
        val capture = CapturingRenderer()
        val registry = BlockRendererRegistry(
            mapOf(
                "conditional" to ConditionalBlockRenderer(),
                "text" to capture,
            ),
        )

        val conditional = ConditionalBlock(
            id = "conditional-1",
            styles = mapOf("color" to "#111111"),
            condition = Expression(raw = "true", language = ExpressionLanguage.JavaScript),
            children = listOf(TextBlock(id = "text-1")),
        )

        registry.render(
            conditional,
            createContext(inheritedStyles = mapOf("color" to "#333333")),
        )

        val childContext = capture.contexts.single()
        assertEquals("#111111", childContext.inheritedStyles["color"])
    }

    @Test
    fun `loop renderer forwards inherited styles for each iteration`() {
        val capture = CapturingRenderer()
        val registry = BlockRendererRegistry(
            mapOf(
                "loop" to LoopBlockRenderer(),
                "text" to capture,
            ),
        )

        val loop = LoopBlock(
            id = "loop-1",
            styles = mapOf("fontSize" to "2rem"),
            expression = Expression(raw = "items", language = ExpressionLanguage.SimplePath),
            itemAlias = "item",
            children = listOf(TextBlock(id = "text-1")),
        )

        registry.render(
            loop,
            createContext(
                data = mapOf("items" to listOf("a", "b")),
                inheritedStyles = mapOf("fontSize" to "4rem"),
            ),
        )

        assertEquals(2, capture.contexts.size)
        assertTrue(capture.contexts.all { it.inheritedStyles["fontSize"] == "2rem" })
        assertTrue(capture.contexts.all { it.loopContext["item"] != null })
    }
}

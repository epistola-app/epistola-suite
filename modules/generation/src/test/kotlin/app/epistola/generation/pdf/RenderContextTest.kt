package app.epistola.generation.pdf

import app.epistola.generation.TipTapConverter
import app.epistola.generation.expression.CompositeExpressionEvaluator
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RenderContextTest {
    private val evaluator = CompositeExpressionEvaluator()
    private val fontCache = FontCache(pdfaCompliant = false)
    private val tipTapConverter = TipTapConverter(evaluator)
    private val minimalDocument = TemplateDocument(
        root = "root",
        nodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf("s1"))),
        slots = mapOf("s1" to Slot(id = "s1", nodeId = "root", name = "children", children = emptyList())),
    )

    private fun createContext(
        data: Map<String, Any?> = mapOf("name" to "John"),
        systemParams: Map<String, Any?> = emptyMap(),
    ) = RenderContext(
        data = data,
        expressionEvaluator = evaluator,
        tipTapConverter = tipTapConverter,
        fontCache = fontCache,
        document = minimalDocument,
        systemParams = systemParams,
    )

    @Test
    fun `effectiveData returns data as-is when no system params`() {
        val context = createContext()
        assertSame(context.data, context.effectiveData, "Should return same reference when no system params")
    }

    @Test
    fun `effectiveData merges system params under sys key`() {
        val context = createContext(
            systemParams = mapOf("pages" to mapOf("current" to 3)),
        )

        val effective = context.effectiveData
        assertEquals("John", effective["name"])

        @Suppress("UNCHECKED_CAST")
        val sys = effective["sys"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val pages = sys["pages"] as Map<String, Any?>
        assertEquals(3, pages["current"])
    }

    @Test
    fun `effectiveData preserves original data fields`() {
        val data = mapOf("a" to 1, "b" to "two")
        val context = createContext(
            data = data,
            systemParams = mapOf("pages" to mapOf("current" to 1)),
        )

        val effective = context.effectiveData
        assertEquals(1, effective["a"])
        assertEquals("two", effective["b"])
    }

    @Test
    fun `withPageParams injects page params`() {
        val context = createContext()
        val pageContext = context.withPageParams(5, 10)

        @Suppress("UNCHECKED_CAST")
        val pages = pageContext.systemParams["pages"] as Map<String, Any?>
        assertEquals(5, pages["current"])
        assertEquals(10, pages["total"])
    }

    @Test
    fun `withPageParams preserves existing system params`() {
        val context = createContext(
            systemParams = mapOf("existing" to "value"),
        )
        val pageContext = context.withPageParams(2, 5)

        assertEquals("value", pageContext.systemParams["existing"])

        @Suppress("UNCHECKED_CAST")
        val pages = pageContext.systemParams["pages"] as Map<String, Any?>
        assertEquals(2, pages["current"])
        assertEquals(5, pages["total"])
    }

    @Test
    fun `withPageParams creates independent context copy`() {
        val context = createContext()
        val page1 = context.withPageParams(1, 3)
        val page2 = context.withPageParams(2, 3)

        @Suppress("UNCHECKED_CAST")
        val p1 = page1.systemParams["pages"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val p2 = page2.systemParams["pages"] as Map<String, Any?>
        assertEquals(1, p1["current"])
        assertEquals(3, p1["total"])
        assertEquals(2, p2["current"])
        assertEquals(3, p2["total"])
    }

    @Test
    fun `withTotalPages sets totalPages field`() {
        val context = createContext()
        assertEquals(null, context.totalPages)

        val withTotal = context.withTotalPages(10)
        assertEquals(10, withTotal.totalPages)
    }

    @Test
    fun `withTotalPages injects pages total into effectiveData`() {
        val context = createContext()
        val withTotal = context.withTotalPages(10)

        @Suppress("UNCHECKED_CAST")
        val sys = withTotal.effectiveData["sys"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val pages = sys["pages"] as Map<String, Any?>
        assertEquals(10, pages["total"])
    }

    @Test
    fun `withTotalPages placeholder provides sys pages total for body content`() {
        val context = createContext().withTotalPages(DirectPdfRenderer.FIRST_PASS_PAGE_TOTAL_PLACEHOLDER)

        @Suppress("UNCHECKED_CAST")
        val sys = context.effectiveData["sys"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val pages = sys["pages"] as Map<String, Any?>
        assertEquals(DirectPdfRenderer.FIRST_PASS_PAGE_TOTAL_PLACEHOLDER, pages["total"])
    }

    @Test
    fun `effectiveData with withPageParams works end-to-end`() {
        val context = createContext(data = mapOf("greeting" to "Hello"))
        val pageContext = context.withPageParams(7, 20)

        val effective = pageContext.effectiveData
        assertEquals("Hello", effective["greeting"])

        @Suppress("UNCHECKED_CAST")
        val sys = effective["sys"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val pages = sys["pages"] as Map<String, Any?>
        assertEquals(7, pages["current"])
        assertEquals(20, pages["total"])
    }
}

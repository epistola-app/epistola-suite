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
            systemParams = mapOf("page" to mapOf("number" to 3)),
        )

        val effective = context.effectiveData
        assertEquals("John", effective["name"])

        @Suppress("UNCHECKED_CAST")
        val sys = effective["sys"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val page = sys["page"] as Map<String, Any?>
        assertEquals(3, page["number"])
    }

    @Test
    fun `effectiveData preserves original data fields`() {
        val data = mapOf("a" to 1, "b" to "two")
        val context = createContext(
            data = data,
            systemParams = mapOf("page" to mapOf("number" to 1)),
        )

        val effective = context.effectiveData
        assertEquals(1, effective["a"])
        assertEquals("two", effective["b"])
    }

    @Test
    fun `withPageParams injects page number`() {
        val context = createContext()
        val pageContext = context.withPageParams(5)

        @Suppress("UNCHECKED_CAST")
        val page = pageContext.systemParams["page"] as Map<String, Any?>
        assertEquals(5, page["number"])
    }

    @Test
    fun `withPageParams preserves existing system params`() {
        val context = createContext(
            systemParams = mapOf("existing" to "value"),
        )
        val pageContext = context.withPageParams(2)

        assertEquals("value", pageContext.systemParams["existing"])

        @Suppress("UNCHECKED_CAST")
        val page = pageContext.systemParams["page"] as Map<String, Any?>
        assertEquals(2, page["number"])
    }

    @Test
    fun `withPageParams creates independent context copy`() {
        val context = createContext()
        val page1 = context.withPageParams(1)
        val page2 = context.withPageParams(2)

        @Suppress("UNCHECKED_CAST")
        val p1 = page1.systemParams["page"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val p2 = page2.systemParams["page"] as Map<String, Any?>
        assertEquals(1, p1["number"])
        assertEquals(2, p2["number"])
    }

    @Test
    fun `withGlobalParams injects today date`() {
        val context = createContext()
        val globalContext = context.withGlobalParams()

        assertEquals(java.time.LocalDate.now().toString(), globalContext.systemParams["today"])
    }

    @Test
    fun `withGlobalParams makes today available in effectiveData`() {
        val context = createContext(data = mapOf("name" to "John"))
        val globalContext = context.withGlobalParams()

        val effective = globalContext.effectiveData
        assertEquals("John", effective["name"])

        @Suppress("UNCHECKED_CAST")
        val sys = effective["sys"] as Map<String, Any?>
        assertEquals(java.time.LocalDate.now().toString(), sys["today"])
    }

    @Test
    fun `withGlobalParams preserves existing system params`() {
        val context = createContext(
            systemParams = mapOf("page" to mapOf("number" to 5)),
        )
        val globalContext = context.withGlobalParams()

        @Suppress("UNCHECKED_CAST")
        val page = globalContext.systemParams["page"] as Map<String, Any?>
        assertEquals(5, page["number"])
        assertEquals(java.time.LocalDate.now().toString(), globalContext.systemParams["today"])
    }

    @Test
    fun `withGlobalParams creates independent context copy`() {
        val context = createContext()
        val global1 = context.withGlobalParams()
        val global2 = context.withGlobalParams()

        assertEquals(java.time.LocalDate.now().toString(), global1.systemParams["today"])
        assertEquals(java.time.LocalDate.now().toString(), global2.systemParams["today"])
        assertSame(context.data, global1.data)
        assertSame(context.data, global2.data)
    }

    @Test
    fun `effectiveData with withPageParams works end-to-end`() {
        val context = createContext(data = mapOf("greeting" to "Hello"))
        val pageContext = context.withPageParams(7)

        val effective = pageContext.effectiveData
        assertEquals("Hello", effective["greeting"])

        @Suppress("UNCHECKED_CAST")
        val sys = effective["sys"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val page = sys["page"] as Map<String, Any?>
        assertEquals(7, page["number"])
    }
}

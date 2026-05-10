package app.epistola.generation.pdf

import app.epistola.generation.TipTapConverter
import app.epistola.generation.expression.CompositeExpressionEvaluator
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class ParameterScopeTest {
    private val evaluator = CompositeExpressionEvaluator()
    private val fontCache = FontCache(pdfaCompliant = false)
    private val tipTapConverter = TipTapConverter(evaluator)

    private val minimalDocument = TemplateDocument(
        root = "root",
        nodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf("s1"))),
        slots = mapOf("s1" to Slot(id = "s1", nodeId = "root", name = "children", children = emptyList())),
    )

    private fun stencilNode(
        bindings: Map<String, Any?>? = null,
        alias: String? = null,
    ): Node {
        val props = mutableMapOf<String, Any?>("stencilId" to "x", "version" to 1)
        if (bindings != null) props["parameterBindings"] = bindings
        if (alias != null) props["paramsAlias"] = alias
        return Node(id = "n1", type = "stencil", props = props)
    }

    private fun createContext(
        data: Map<String, Any?> = emptyMap(),
        renderMode: RenderMode = RenderMode.STRICT,
        parameterScopes: Map<String, Map<String, Any?>> = emptyMap(),
    ) = RenderContext(
        data = data,
        expressionEvaluator = evaluator,
        tipTapConverter = tipTapConverter,
        fontCache = fontCache,
        document = minimalDocument,
        renderMode = renderMode,
        parameterScopes = parameterScopes,
    )

    @Test
    fun `null schema returns the outer context unchanged`() {
        val outer = createContext()
        val result = ParameterScope.push(stencilNode(), schema = null, outer = outer)
        assertSame(outer, result)
    }

    @Test
    fun `schema without properties returns the outer context unchanged`() {
        val outer = createContext()
        val result = ParameterScope.push(
            stencilNode(),
            schema = mapOf("type" to "object"),
            outer = outer,
        )
        assertSame(outer, result)
    }

    @Test
    fun `evaluates a simple path binding against outer effectiveData`() {
        val outer = createContext(data = mapOf("customer" to mapOf("name" to "Alice")))
        val ctx = ParameterScope.push(
            stencilNode(bindings = mapOf("name" to "customer.name")),
            schema = mapOf("properties" to mapOf("name" to mapOf("type" to "string"))),
            outer = outer,
        )
        assertEquals(mapOf("name" to "Alice"), ctx.parameterScopes["params"])
    }

    @Test
    fun `JSONata literal binding evaluates without outer data`() {
        val outer = createContext()
        val ctx = ParameterScope.push(
            stencilNode(bindings = mapOf("greeting" to "'hi'")),
            schema = mapOf("properties" to mapOf("greeting" to mapOf("type" to "string"))),
            outer = outer,
        )
        assertEquals("hi", ctx.parameterScopes["params"]?.get("greeting"))
    }

    @Test
    fun `concatenation expression evaluates against outer data`() {
        val outer = createContext(data = mapOf("a" to "Hello", "b" to "World"))
        val ctx = ParameterScope.push(
            stencilNode(bindings = mapOf("msg" to "a & ' ' & b")),
            schema = mapOf("properties" to mapOf("msg" to mapOf("type" to "string"))),
            outer = outer,
        )
        assertEquals("Hello World", ctx.parameterScopes["params"]?.get("msg"))
    }

    @Test
    fun `unbound parameter falls back to schema default`() {
        val outer = createContext()
        val ctx = ParameterScope.push(
            stencilNode(bindings = emptyMap()),
            schema = mapOf(
                "properties" to mapOf(
                    "name" to mapOf("type" to "string", "default" to "Anonymous"),
                ),
            ),
            outer = outer,
        )
        assertEquals("Anonymous", ctx.parameterScopes["params"]?.get("name"))
    }

    @Test
    fun `unbound non-required parameter without default yields null`() {
        val outer = createContext()
        val ctx = ParameterScope.push(
            stencilNode(bindings = emptyMap()),
            schema = mapOf("properties" to mapOf("name" to mapOf("type" to "string"))),
            outer = outer,
        )
        assertNull(ctx.parameterScopes["params"]?.get("name"))
    }

    @Test
    fun `STRICT mode throws on required parameter with no binding nor default`() {
        val outer = createContext(renderMode = RenderMode.STRICT)
        assertFailsWith<IllegalStateException> {
            ParameterScope.push(
                stencilNode(bindings = emptyMap()),
                schema = mapOf(
                    "properties" to mapOf("name" to mapOf("type" to "string")),
                    "required" to listOf("name"),
                ),
                outer = outer,
            )
        }
    }

    @Test
    fun `PREVIEW mode substitutes a visible placeholder for required without binding nor default`() {
        val outer = createContext(renderMode = RenderMode.PREVIEW)
        val ctx = ParameterScope.push(
            stencilNode(bindings = emptyMap()),
            schema = mapOf(
                "properties" to mapOf("name" to mapOf("type" to "string")),
                "required" to listOf("name"),
            ),
            outer = outer,
        )
        // PREVIEW shows `<name>` so the preview pane mirrors the editor canvas
        // and the user notices the missing binding before strict-mode delivery.
        assertEquals("<name>", ctx.parameterScopes["params"]?.get("name"))
    }

    @Test
    fun `PREVIEW mode prefers the schema default over the placeholder when one is set`() {
        val outer = createContext(renderMode = RenderMode.PREVIEW)
        val ctx = ParameterScope.push(
            stencilNode(bindings = emptyMap()),
            schema = mapOf(
                "properties" to mapOf("name" to mapOf("type" to "string", "default" to "fallback")),
                "required" to listOf("name"),
            ),
            outer = outer,
        )
        assertEquals("fallback", ctx.parameterScopes["params"]?.get("name"))
    }

    @Test
    fun `default alias is 'params' when paramsAlias prop is absent`() {
        val outer = createContext()
        val ctx = ParameterScope.push(
            stencilNode(bindings = mapOf("a" to "'x'")),
            schema = mapOf("properties" to mapOf("a" to mapOf("type" to "string"))),
            outer = outer,
        )
        assertEquals("x", ctx.parameterScopes["params"]?.get("a"))
    }

    @Test
    fun `paramsAlias prop overrides the default alias`() {
        val outer = createContext()
        val ctx = ParameterScope.push(
            stencilNode(bindings = mapOf("title" to "'Hello'"), alias = "letter"),
            schema = mapOf("properties" to mapOf("title" to mapOf("type" to "string"))),
            outer = outer,
        )
        assertEquals("Hello", ctx.parameterScopes["letter"]?.get("title"))
        assertNull(ctx.parameterScopes["params"]) // not under default alias
    }

    @Test
    fun `existing scopes under different aliases are preserved`() {
        val outer = createContext(
            parameterScopes = mapOf("letter" to mapOf("title" to "Outer")),
        )
        val ctx = ParameterScope.push(
            stencilNode(bindings = mapOf("name" to "'Inner'")),
            schema = mapOf("properties" to mapOf("name" to mapOf("type" to "string"))),
            outer = outer,
        )
        // Outer scope retained.
        assertEquals("Outer", ctx.parameterScopes["letter"]?.get("title"))
        // Inner scope added under default alias.
        assertEquals("Inner", ctx.parameterScopes["params"]?.get("name"))
    }

    @Test
    fun `same alias intentionally shadows the outer scope`() {
        val outer = createContext(
            parameterScopes = mapOf("params" to mapOf("a" to "outer-a", "b" to "outer-b")),
        )
        val ctx = ParameterScope.push(
            stencilNode(bindings = mapOf("a" to "'inner-a'")),
            schema = mapOf("properties" to mapOf("a" to mapOf("type" to "string"))),
            outer = outer,
        )
        // Inner alias replaces the outer's params map entirely (no merge).
        assertEquals("inner-a", ctx.parameterScopes["params"]?.get("a"))
        assertNull(ctx.parameterScopes["params"]?.get("b"))
    }

    @Test
    fun `effectiveData exposes the new alias as a top-level key`() {
        val outer = createContext(data = mapOf("customer" to mapOf("name" to "Alice")))
        val ctx = ParameterScope.push(
            stencilNode(bindings = mapOf("greeting" to "customer.name")),
            schema = mapOf("properties" to mapOf("greeting" to mapOf("type" to "string"))),
            outer = outer,
        )

        @Suppress("UNCHECKED_CAST")
        val params = ctx.effectiveData["params"] as Map<String, Any?>
        assertEquals("Alice", params["greeting"])
    }
}

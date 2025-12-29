package app.epistola.generation.expression

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaScriptEvaluatorTest {
    private val evaluator = JavaScriptEvaluator()

    @Test
    fun `evaluate simple path`() {
        val data = mapOf("name" to "John")
        assertEquals("John", evaluator.evaluate("name", data))
    }

    @Test
    fun `evaluate nested path`() {
        val data = mapOf(
            "customer" to mapOf(
                "name" to "Alice",
                "address" to mapOf("city" to "Amsterdam"),
            ),
        )
        assertEquals("Alice", evaluator.evaluate("customer.name", data))
        assertEquals("Amsterdam", evaluator.evaluate("customer.address.city", data))
    }

    @Test
    fun `evaluate array access`() {
        val data = mapOf(
            "items" to listOf("first", "second", "third"),
        )
        assertEquals("first", evaluator.evaluate("items[0]", data))
        assertEquals("second", evaluator.evaluate("items[1]", data))
    }

    @Test
    fun `evaluate array filter`() {
        val data = mapOf(
            "items" to listOf(
                mapOf("name" to "A", "active" to true),
                mapOf("name" to "B", "active" to false),
                mapOf("name" to "C", "active" to true),
            ),
        )
        val result = evaluator.evaluate("items.filter(x => x.active)", data) as List<*>
        assertEquals(2, result.size)
    }

    @Test
    fun `evaluate array map`() {
        val data = mapOf(
            "products" to listOf(
                mapOf("price" to 10),
                mapOf("price" to 20),
                mapOf("price" to 30),
            ),
        )
        val result = evaluator.evaluate("products.map(x => x.price)", data)
        assertEquals(listOf(10L, 20L, 30L), result)
    }

    @Test
    fun `evaluate array reduce`() {
        val data = mapOf(
            "items" to listOf(
                mapOf("price" to 10),
                mapOf("price" to 20),
                mapOf("price" to 30),
            ),
        )
        assertEquals(60L, evaluator.evaluate("items.reduce((sum, x) => sum + x.price, 0)", data))
    }

    @Test
    fun `evaluate string concatenation`() {
        val data = mapOf("first" to "John", "last" to "Doe")
        assertEquals("John Doe", evaluator.evaluate("first + \" \" + last", data))
    }

    @Test
    fun `evaluate template literal`() {
        val data = mapOf("name" to "John", "age" to 30)
        assertEquals("John is 30", evaluator.evaluate("`\${name} is \${age}`", data))
    }

    @Test
    fun `evaluate conditional expression`() {
        val data = mapOf("active" to true)
        assertEquals("Yes", evaluator.evaluate("active ? \"Yes\" : \"No\"", data))

        val data2 = mapOf("active" to false)
        assertEquals("No", evaluator.evaluate("active ? \"Yes\" : \"No\"", data2))
    }

    @Test
    fun `evaluate arithmetic`() {
        val data = mapOf("a" to 10, "b" to 5)
        assertEquals(15L, evaluator.evaluate("a + b", data))
        assertEquals(5L, evaluator.evaluate("a - b", data))
        assertEquals(50L, evaluator.evaluate("a * b", data))
        assertEquals(2L, evaluator.evaluate("a / b", data))
    }

    @Test
    fun `evaluate returns null for missing path`() {
        val data = mapOf("name" to "John")
        assertNull(evaluator.evaluate("missing", data))
    }

    @Test
    fun `evaluate returns null for empty expression`() {
        val data = mapOf("name" to "John")
        assertNull(evaluator.evaluate("", data))
        assertNull(evaluator.evaluate("  ", data))
    }

    @Test
    fun `evaluateToString formats values correctly`() {
        val data = mapOf(
            "string" to "hello",
            "number" to 42,
            "boolean" to true,
        )
        assertEquals("hello", evaluator.evaluateToString("string", data))
        assertEquals("42", evaluator.evaluateToString("number", data))
        assertEquals("true", evaluator.evaluateToString("boolean", data))
        assertEquals("", evaluator.evaluateToString("missing", data))
    }

    @Test
    fun `evaluateCondition with truthy values`() {
        val data = mapOf(
            "trueBoolean" to true,
            "nonEmptyString" to "hello",
            "nonZeroNumber" to 42,
        )
        assertTrue(evaluator.evaluateCondition("trueBoolean", data))
        assertTrue(evaluator.evaluateCondition("nonEmptyString", data))
        assertTrue(evaluator.evaluateCondition("nonZeroNumber", data))
    }

    @Test
    fun `evaluateCondition with falsy values`() {
        val data = mapOf(
            "falseBoolean" to false,
            "emptyString" to "",
            "zero" to 0,
        )
        assertFalse(evaluator.evaluateCondition("falseBoolean", data))
        assertFalse(evaluator.evaluateCondition("emptyString", data))
        assertFalse(evaluator.evaluateCondition("zero", data))
        assertFalse(evaluator.evaluateCondition("missing", data))
    }

    @Test
    fun `evaluateIterable returns list`() {
        val data = mapOf(
            "items" to listOf("a", "b", "c"),
        )
        assertEquals(listOf("a", "b", "c"), evaluator.evaluateIterable("items", data))
        assertEquals(emptyList<Any?>(), evaluator.evaluateIterable("missing", data))
    }

    @Test
    fun `processTemplate replaces expressions`() {
        val data = mapOf(
            "name" to "John",
            "company" to "Acme",
        )
        assertEquals(
            "Hello John from Acme!",
            evaluator.processTemplate("Hello {{name}} from {{company}}!", data),
        )
    }

    @Test
    fun `evaluate with loop context`() {
        val data = mapOf("global" to "value")
        val loopContext = mapOf("item" to mapOf("name" to "Product"))
        assertEquals("Product", evaluator.evaluate("item.name", data, loopContext))
        assertEquals("value", evaluator.evaluate("global", data, loopContext))
    }

    @Test
    fun `loop context overrides data`() {
        val data = mapOf("item" to mapOf("name" to "DataValue"))
        val loopContext = mapOf("item" to mapOf("name" to "LoopValue"))
        assertEquals("LoopValue", evaluator.evaluate("item.name", data, loopContext))
    }

    @Test
    fun `sandbox prevents dangerous operations`() {
        val data = emptyMap<String, Any?>()

        // These should all return null or fail silently due to sandbox
        assertNull(evaluator.evaluate("require('fs')", data))
        assertNull(evaluator.evaluate("process.exit(1)", data))
        assertNull(evaluator.evaluate("Deno.readTextFile('/etc/passwd')", data))
    }
}

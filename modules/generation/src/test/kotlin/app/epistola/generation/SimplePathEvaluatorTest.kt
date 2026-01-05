package app.epistola.generation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimplePathEvaluatorTest {
    private val evaluator = SimplePathEvaluator()

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
    fun `evaluate array index`() {
        val data = mapOf(
            "items" to listOf("first", "second", "third"),
        )
        assertEquals("first", evaluator.evaluate("items[0]", data))
        assertEquals("second", evaluator.evaluate("items[1]", data))
        assertEquals("third", evaluator.evaluate("items[2]", data))
    }

    @Test
    fun `evaluate array of objects`() {
        val data = mapOf(
            "products" to listOf(
                mapOf("name" to "Widget"),
                mapOf("name" to "Gadget"),
            ),
        )
        assertEquals("Widget", evaluator.evaluate("products[0].name", data))
        assertEquals("Gadget", evaluator.evaluate("products[1].name", data))
    }

    @Test
    fun `evaluate returns null for missing path`() {
        val data = mapOf("name" to "John")
        assertNull(evaluator.evaluate("missing", data))
        assertNull(evaluator.evaluate("name.missing", data))
    }

    @Test
    fun `evaluateToString formats values correctly`() {
        val data = mapOf(
            "string" to "hello",
            "number" to 42,
            "decimal" to 3.14,
            "boolean" to true,
            "null" to null,
        )
        assertEquals("hello", evaluator.evaluateToString("string", data))
        assertEquals("42", evaluator.evaluateToString("number", data))
        assertEquals("3.14", evaluator.evaluateToString("decimal", data))
        assertEquals("true", evaluator.evaluateToString("boolean", data))
        assertEquals("", evaluator.evaluateToString("null", data))
        assertEquals("", evaluator.evaluateToString("missing", data))
    }

    @Test
    fun `evaluateCondition with truthy values`() {
        val data = mapOf(
            "trueBoolean" to true,
            "nonEmptyString" to "hello",
            "nonZeroNumber" to 42,
            "nonEmptyList" to listOf("a"),
        )
        assertTrue(evaluator.evaluateCondition("trueBoolean", data))
        assertTrue(evaluator.evaluateCondition("nonEmptyString", data))
        assertTrue(evaluator.evaluateCondition("nonZeroNumber", data))
        assertTrue(evaluator.evaluateCondition("nonEmptyList", data))
    }

    @Test
    fun `evaluateCondition with falsy values`() {
        val data = mapOf(
            "falseBoolean" to false,
            "emptyString" to "",
            "zero" to 0,
            "emptyList" to emptyList<String>(),
            "null" to null,
        )
        assertFalse(evaluator.evaluateCondition("falseBoolean", data))
        assertFalse(evaluator.evaluateCondition("emptyString", data))
        assertFalse(evaluator.evaluateCondition("zero", data))
        assertFalse(evaluator.evaluateCondition("emptyList", data))
        assertFalse(evaluator.evaluateCondition("null", data))
        assertFalse(evaluator.evaluateCondition("missing", data))
    }

    @Test
    fun `evaluateIterable returns list`() {
        val data = mapOf(
            "items" to listOf("a", "b", "c"),
            "notAList" to "string",
        )
        assertEquals(listOf("a", "b", "c"), evaluator.evaluateIterable("items", data))
        // Non-iterables are wrapped in a single-element list
        assertEquals(listOf("string"), evaluator.evaluateIterable("notAList", data))
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
    fun `processTemplate with missing values`() {
        val data = mapOf("name" to "John")
        assertEquals(
            "Hello John from !",
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
}

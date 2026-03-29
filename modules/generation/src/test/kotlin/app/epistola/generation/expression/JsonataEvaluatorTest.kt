package app.epistola.generation.expression

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonataEvaluatorTest {
    private val evaluator = JsonataEvaluator()

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
    fun `evaluate array mapping`() {
        val data = mapOf(
            "products" to listOf(
                mapOf("price" to 10),
                mapOf("price" to 20),
                mapOf("price" to 30),
            ),
        )
        // JSONata: products.price returns an array of prices
        val result = evaluator.evaluate("products.price", data)
        assertEquals(listOf(10, 20, 30), result)
    }

    @Test
    fun `evaluate sum aggregation`() {
        val data = mapOf(
            "items" to listOf(
                mapOf("price" to 10),
                mapOf("price" to 20),
                mapOf("price" to 30),
            ),
        )
        assertEquals(60, evaluator.evaluate("\$sum(items.price)", data))
    }

    @Test
    fun `evaluate string concatenation`() {
        val data = mapOf("first" to "John", "last" to "Doe")
        assertEquals("John Doe", evaluator.evaluate("first & \" \" & last", data))
    }

    @Test
    fun `evaluate conditional expression`() {
        val data = mapOf("active" to true)
        assertEquals("Yes", evaluator.evaluate("active ? \"Yes\" : \"No\"", data))

        val data2 = mapOf("active" to false)
        assertEquals("No", evaluator.evaluate("active ? \"Yes\" : \"No\"", data2))
    }

    @Test
    fun `evaluate filter expression`() {
        val data = mapOf(
            "items" to listOf(
                mapOf("name" to "A", "active" to true),
                mapOf("name" to "B", "active" to false),
                mapOf("name" to "C", "active" to true),
            ),
        )
        // JSONata: items[active] filters to only active items
        val result = evaluator.evaluate("items[active]", data) as List<*>
        assertEquals(2, result.size)
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
            "decimal" to 3.14,
            "boolean" to true,
        )
        assertEquals("hello", evaluator.evaluateToString("string", data))
        assertEquals("42", evaluator.evaluateToString("number", data))
        assertEquals("3.14", evaluator.evaluateToString("decimal", data))
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

    // --- $formatDate custom function ---

    @Test
    fun `formatDate with dd-MM-yyyy`() {
        val data = mapOf("d" to "2024-01-15")
        assertEquals("15-01-2024", evaluator.evaluate("\$formatDate(d, 'dd-MM-yyyy')", data))
    }

    @Test
    fun `formatDate with dd slash MM slash yyyy`() {
        val data = mapOf("d" to "2024-01-15")
        assertEquals("15/01/2024", evaluator.evaluate("\$formatDate(d, 'dd/MM/yyyy')", data))
    }

    @Test
    fun `formatDate with d MMMM yyyy`() {
        val data = mapOf("d" to "2024-01-15")
        assertEquals("15 January 2024", evaluator.evaluate("\$formatDate(d, 'd MMMM yyyy')", data))
    }

    @Test
    fun `formatDate with d MMMM yyyy no leading zero`() {
        val data = mapOf("d" to "2024-07-05")
        assertEquals("5 July 2024", evaluator.evaluate("\$formatDate(d, 'd MMMM yyyy')", data))
    }

    @Test
    fun `formatDate with invalid date returns raw value`() {
        val data = mapOf("d" to "not-a-date")
        assertEquals("not-a-date", evaluator.evaluate("\$formatDate(d, 'dd-MM-yyyy')", data))
    }

    @Test
    fun `formatDate in string concatenation`() {
        val data = mapOf("dueDate" to "2024-02-15")
        assertEquals(
            "Due: 15-02-2024",
            evaluator.evaluate("\"Due: \" & \$formatDate(dueDate, 'dd-MM-yyyy')", data),
        )
    }

    @Test
    fun `formatDate with missing field returns null`() {
        val data = mapOf("other" to "2024-01-15")
        assertNull(evaluator.evaluate("\$formatDate(missing, 'dd-MM-yyyy')", data))
    }

    // --- $formatDate with datetimes ---

    @Test
    fun `formatDate with local datetime`() {
        val data = mapOf("ts" to "2024-01-15T14:30:00")
        assertEquals(
            "15-01-2024 14:30",
            evaluator.evaluate("\$formatDate(ts, 'dd-MM-yyyy HH:mm')", data),
        )
    }

    @Test
    fun `formatDate with UTC datetime converts to Amsterdam timezone`() {
        // UTC 12:00 → Amsterdam CET is UTC+1 → 13:00
        val data = mapOf("ts" to "2024-01-15T12:00:00Z")
        assertEquals(
            "15-01-2024 13:00",
            evaluator.evaluate("\$formatDate(ts, 'dd-MM-yyyy HH:mm')", data),
        )
    }

    @Test
    fun `formatDate with offset datetime converts to Amsterdam timezone`() {
        // +05:00 at 17:00 → UTC 12:00 → Amsterdam CET (UTC+1) → 13:00
        val data = mapOf("ts" to "2024-01-15T17:00:00+05:00")
        assertEquals(
            "15-01-2024 13:00",
            evaluator.evaluate("\$formatDate(ts, 'dd-MM-yyyy HH:mm')", data),
        )
    }

    @Test
    fun `formatDate with UTC datetime and date-only pattern`() {
        val data = mapOf("ts" to "2024-01-15T14:30:00Z")
        assertEquals(
            "15-01-2024",
            evaluator.evaluate("\$formatDate(ts, 'dd-MM-yyyy')", data),
        )
    }

    @Test
    fun `formatDate with datetime including seconds`() {
        val data = mapOf("ts" to "2024-01-15T14:30:45")
        assertEquals(
            "14:30:45",
            evaluator.evaluate("\$formatDate(ts, 'HH:mm:ss')", data),
        )
    }
}

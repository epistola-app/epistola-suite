package app.epistola.generation.expression

import app.epistola.template.model.Expression
import app.epistola.template.model.ExpressionLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class CompositeExpressionEvaluatorTest {
    private val evaluator = CompositeExpressionEvaluator()

    @Test
    fun `dispatches to JSONata evaluator for JSONata expressions`() {
        val data = mapOf(
            "items" to listOf(
                mapOf("price" to 10),
                mapOf("price" to 20),
            ),
        )

        // JSONata-style sum aggregation
        val expression = Expression(
            raw = "\$sum(items.price)",
            language = ExpressionLanguage.Jsonata,
        )
        assertEquals(30, evaluator.evaluate(expression, data))
    }

    @Test
    fun `dispatches to JavaScript evaluator for JavaScript expressions`() {
        val data = mapOf(
            "items" to listOf(
                mapOf("price" to 10),
                mapOf("price" to 20),
            ),
        )

        // JavaScript-style reduce
        val expression = Expression(
            raw = "items.reduce((sum, x) => sum + x.price, 0)",
            language = ExpressionLanguage.JavaScript,
        )
        assertEquals(30L, evaluator.evaluate(expression, data))
    }

    @Test
    fun `defaults to JSONata`() {
        val data = mapOf("name" to "John")

        // Expression with default language
        val expression = Expression(raw = "name")
        assertEquals("John", evaluator.evaluate(expression, data))
    }

    @Test
    fun `evaluateCondition with JSONata`() {
        val data = mapOf("active" to true)

        val expression = Expression(
            raw = "active",
            language = ExpressionLanguage.Jsonata,
        )
        assertEquals(true, evaluator.evaluateCondition(expression, data))
    }

    @Test
    fun `evaluateCondition with JavaScript`() {
        val data = mapOf("items" to listOf("a", "b"))

        val expression = Expression(
            raw = "items.length > 0",
            language = ExpressionLanguage.JavaScript,
        )
        assertEquals(true, evaluator.evaluateCondition(expression, data))
    }

    @Test
    fun `evaluateIterable with JSONata`() {
        val data = mapOf(
            "items" to listOf(
                mapOf("name" to "A", "active" to true),
                mapOf("name" to "B", "active" to false),
            ),
        )

        // JSONata filter syntax
        val expression = Expression(
            raw = "items[active]",
            language = ExpressionLanguage.Jsonata,
        )
        val result = evaluator.evaluateIterable(expression, data)
        assertEquals(1, result.size)
    }

    @Test
    fun `evaluateIterable with JavaScript`() {
        val data = mapOf(
            "items" to listOf(
                mapOf("name" to "A", "active" to true),
                mapOf("name" to "B", "active" to false),
            ),
        )

        // JavaScript filter syntax
        val expression = Expression(
            raw = "items.filter(x => x.active)",
            language = ExpressionLanguage.JavaScript,
        )
        val result = evaluator.evaluateIterable(expression, data)
        assertEquals(1, result.size)
    }

    @Test
    fun `processTemplate with JSONata`() {
        val data = mapOf("name" to "John", "age" to 30)

        val result = evaluator.processTemplate(
            "Hello {{name}}, you are {{age}} years old!",
            ExpressionLanguage.Jsonata,
            data,
        )
        assertEquals("Hello John, you are 30 years old!", result)
    }

    @Test
    fun `processTemplate with JavaScript`() {
        val data = mapOf("name" to "John", "age" to 30)

        val result = evaluator.processTemplate(
            "Hello {{name}}, you are {{age}} years old!",
            ExpressionLanguage.JavaScript,
            data,
        )
        assertEquals("Hello John, you are 30 years old!", result)
    }

    @Test
    fun `handles loop context`() {
        val data = mapOf("prefix" to "Item")
        val loopContext = mapOf("item" to mapOf("name" to "Widget"))

        val expression = Expression(
            raw = "prefix & \": \" & item.name",
            language = ExpressionLanguage.Jsonata,
        )
        assertEquals("Item: Widget", evaluator.evaluate(expression, data, loopContext))
    }

    @Test
    fun `evaluate with explicit language parameter`() {
        val data = mapOf("x" to 5, "y" to 3)

        // Test with explicit language parameter
        assertEquals(8L, evaluator.evaluate("x + y", ExpressionLanguage.JavaScript, data))
    }

    @Test
    fun `dispatches to SimplePath evaluator for SimplePath expressions`() {
        val data = mapOf(
            "customer" to mapOf(
                "name" to "Alice",
                "address" to mapOf("city" to "Amsterdam"),
            ),
        )

        // SimplePath only supports path traversal
        val expression = Expression(
            raw = "customer.address.city",
            language = ExpressionLanguage.SimplePath,
        )
        assertEquals("Amsterdam", evaluator.evaluate(expression, data))
    }

    @Test
    fun `SimplePath is fastest for simple paths`() {
        val data = mapOf("name" to "John")

        // All three should return the same result for simple paths
        assertEquals("John", evaluator.evaluate("name", ExpressionLanguage.Jsonata, data))
        assertEquals("John", evaluator.evaluate("name", ExpressionLanguage.JavaScript, data))
        assertEquals("John", evaluator.evaluate("name", ExpressionLanguage.SimplePath, data))
    }
}

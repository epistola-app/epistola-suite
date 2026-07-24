// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.expression

import app.epistola.generation.RenderCulture
import app.epistola.template.model.Expression
import app.epistola.template.model.ExpressionLanguage
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

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
            language = ExpressionLanguage.jsonata,
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
            language = ExpressionLanguage.javascript,
        )
        assertEquals(30L, evaluator.evaluate(expression, data))
    }

    @Test
    fun `defaults to JSONata`() {
        val data = mapOf("name" to "John")

        val expression = Expression(raw = "name", language = ExpressionLanguage.jsonata)
        assertEquals("John", evaluator.evaluate(expression, data))
    }

    @Test
    fun `evaluateCondition with JSONata`() {
        val data = mapOf("active" to true)

        val expression = Expression(
            raw = "active",
            language = ExpressionLanguage.jsonata,
        )
        assertEquals(true, evaluator.evaluateCondition(expression, data))
    }

    @Test
    fun `evaluateCondition with JavaScript`() {
        val data = mapOf("items" to listOf("a", "b"))

        val expression = Expression(
            raw = "items.length > 0",
            language = ExpressionLanguage.javascript,
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
            language = ExpressionLanguage.jsonata,
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
            language = ExpressionLanguage.javascript,
        )
        val result = evaluator.evaluateIterable(expression, data)
        assertEquals(1, result.size)
    }

    @Test
    fun `processTemplate with JSONata`() {
        val data = mapOf("name" to "John", "age" to 30)

        val result = evaluator.processTemplate(
            "Hello {{name}}, you are {{age}} years old!",
            ExpressionLanguage.jsonata,
            data,
        )
        assertEquals("Hello John, you are 30 years old!", result)
    }

    @Test
    fun `processTemplate with JavaScript`() {
        val data = mapOf("name" to "John", "age" to 30)

        val result = evaluator.processTemplate(
            "Hello {{name}}, you are {{age}} years old!",
            ExpressionLanguage.javascript,
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
            language = ExpressionLanguage.jsonata,
        )
        assertEquals("Item: Widget", evaluator.evaluate(expression, data, loopContext))
    }

    @Test
    fun `evaluate with explicit language parameter`() {
        val data = mapOf("x" to 5, "y" to 3)

        // Test with explicit language parameter
        assertEquals(8L, evaluator.evaluate("x + y", ExpressionLanguage.javascript, data))
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
            language = ExpressionLanguage.simple_path,
        )
        assertEquals("Amsterdam", evaluator.evaluate(expression, data))
    }

    @Test
    fun `SimplePath is fastest for simple paths`() {
        val data = mapOf("name" to "John")

        // All three should return the same result for simple paths
        assertEquals("John", evaluator.evaluate("name", ExpressionLanguage.jsonata, data))
        assertEquals("John", evaluator.evaluate("name", ExpressionLanguage.javascript, data))
        assertEquals("John", evaluator.evaluate("name", ExpressionLanguage.simple_path, data))
    }

    // --- forCulture ---

    @Test
    fun `forCulture returns the same instance for the default culture (no allocation)`() {
        assertSame(evaluator, evaluator.forCulture(RenderCulture.DEFAULT))
    }

    @Test
    fun `forCulture short-circuits for the resolved app-default locale`() {
        // Regression: the resolver yields forLanguageTag("en-US") for the shipped
        // default; that must equal RenderCulture.DEFAULT so the fast path engages.
        val appDefault = RenderCulture(locale = Locale.forLanguageTag("en-US"))
        assertSame(evaluator, evaluator.forCulture(appDefault))
    }

    @Test
    fun `forCulture builds a fresh instance for a non-default locale`() {
        val dutch = evaluator.forCulture(RenderCulture(locale = Locale.forLanguageTag("nl-NL")))
        assertNotSame(evaluator, dutch)
    }

    @Test
    fun `forCulture reuses the JavaScript and SimplePath evaluators (only JSONata is rebuilt)`() {
        val dutch = evaluator.forCulture(RenderCulture(locale = Locale.forLanguageTag("nl-NL")))
        assertSame(evaluator.javaScriptEvaluator, dutch.javaScriptEvaluator)
        assertSame(evaluator.simplePathEvaluator, dutch.simplePathEvaluator)
        assertNotSame(evaluator.jsonataEvaluator, dutch.jsonataEvaluator)
    }

    @Test
    fun `forCulture binds the locale into JSONata number formatting`() {
        val dutch = evaluator.forCulture(RenderCulture(locale = Locale.forLanguageTag("nl-NL")))
        assertEquals(
            "1.234,56",
            dutch.evaluate(
                "\$formatLocaleNumber(amount, '#,##0.00')",
                ExpressionLanguage.jsonata,
                mapOf("amount" to 1234.56),
            ),
        )
    }
}

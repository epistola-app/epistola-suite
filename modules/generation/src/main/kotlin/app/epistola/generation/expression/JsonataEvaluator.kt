package app.epistola.generation.expression

import com.dashjoin.jsonata.Jsonata.jsonata

/**
 * Expression evaluator using JSONata - a query and transformation language for JSON.
 *
 * JSONata provides a concise syntax for:
 * - Property access: `customer.name`
 * - Array filtering: `items[active]`
 * - Mapping: `items.price`
 * - Aggregation: `$sum(items.price)`
 * - String concatenation: `first & " " & last`
 * - Conditionals: `active ? "Yes" : "No"`
 *
 * @see <a href="https://jsonata.org">JSONata Documentation</a>
 */
class JsonataEvaluator : ExpressionEvaluator {
    override fun evaluate(
        expression: String,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?>,
    ): Any? {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) return null

        return try {
            // Merge loop context with data (loop context takes precedence)
            val context = data + loopContext

            // Parse and evaluate the JSONata expression
            val jsonataExpr = jsonata(trimmed)

            // Set reasonable resource limits
            val frame = jsonataExpr.createFrame()
            frame.setRuntimeBounds(5000, 100) // 5 second timeout, max 100 recursion depth

            // Bind loop context variables at the top level for easy access
            loopContext.forEach { (key, value) ->
                frame.bind(key, value)
            }

            jsonataExpr.evaluate(context, frame)
        } catch (e: Exception) {
            // Log or handle evaluation errors
            null
        }
    }
}

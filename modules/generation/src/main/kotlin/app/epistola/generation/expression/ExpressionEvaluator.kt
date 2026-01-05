package app.epistola.generation.expression

/**
 * Interface for evaluating expressions against input data.
 *
 * Implementations provide different expression languages (JSONata, JavaScript).
 */
interface ExpressionEvaluator {
    /**
     * Evaluates an expression against the provided data context.
     *
     * @param expression The expression to evaluate
     * @param data The data context to evaluate against
     * @param loopContext Additional context from loop iterations (e.g., item aliases)
     * @return The resolved value, or null if evaluation fails or returns nothing
     */
    fun evaluate(
        expression: String,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?> = emptyMap(),
    ): Any?

    /**
     * Evaluates an expression and converts the result to a String.
     */
    fun evaluateToString(
        expression: String,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?> = emptyMap(),
    ): String {
        val result = evaluate(expression, data, loopContext)
        return valueToString(result)
    }

    /**
     * Evaluates a condition expression and returns a boolean.
     * Truthy values: non-null, non-empty strings, non-zero numbers, true, non-empty collections
     */
    fun evaluateCondition(
        expression: String,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?> = emptyMap(),
    ): Boolean {
        val result = evaluate(expression, data, loopContext)
        return isTruthy(result)
    }

    /**
     * Evaluates an expression that should return an iterable collection.
     */
    fun evaluateIterable(
        expression: String,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?> = emptyMap(),
    ): List<Any?> {
        val result = evaluate(expression, data, loopContext)
        return when (result) {
            null -> emptyList()
            is List<*> -> result
            is Array<*> -> result.toList()
            is Iterable<*> -> result.toList()
            else -> listOf(result) // Single item becomes a list of one
        }
    }

    /**
     * Processes a string containing embedded expressions like "Hello {{name}}!".
     */
    fun processTemplate(
        template: String,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?> = emptyMap(),
    ): String {
        val pattern = Regex("""\{\{(.+?)\}\}""")
        return pattern.replace(template) { match ->
            val expression = match.groupValues[1]
            evaluateToString(expression, data, loopContext)
        }
    }

    companion object {
        /**
         * Converts a value to a string representation.
         */
        fun valueToString(value: Any?): String = when (value) {
            null -> ""
            is String -> value
            is Number -> if (value.toDouble() == value.toLong().toDouble()) {
                value.toLong().toString()
            } else {
                value.toString()
            }
            is Boolean -> value.toString()
            else -> value.toString()
        }

        /**
         * Determines if a value is truthy.
         */
        fun isTruthy(value: Any?): Boolean = when (value) {
            null -> false
            is Boolean -> value
            is Number -> value.toDouble() != 0.0
            is String -> value.isNotEmpty()
            is Collection<*> -> value.isNotEmpty()
            is Array<*> -> value.isNotEmpty()
            is Map<*, *> -> value.isNotEmpty()
            else -> true
        }
    }
}

package app.epistola.generation

/**
 * Evaluates template expressions like `{{customer.name}}` against input data.
 *
 * Supports:
 * - Simple paths: `customer.name`
 * - Array access: `items.0.name` or `items[0].name`
 * - Nested paths: `order.customer.address.city`
 */
class ExpressionEvaluator {

    /**
     * Evaluates an expression against the provided data context.
     *
     * @param expression The expression to evaluate (e.g., "customer.name")
     * @param data The data context to evaluate against
     * @param loopContext Additional context from loop iterations (e.g., item aliases)
     * @return The resolved value, or null if the path doesn't exist
     */
    fun evaluate(
        expression: String,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?> = emptyMap(),
    ): Any? {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) return null

        // Merge loop context with data (loop context takes precedence)
        val context = data + loopContext

        // Parse the path into segments
        val segments = parsePath(trimmed)
        if (segments.isEmpty()) return null

        return resolvePath(segments, context)
    }

    /**
     * Evaluates an expression and converts the result to a String.
     */
    fun evaluateToString(
        expression: String,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?> = emptyMap(),
    ): String {
        val result = evaluate(expression, data, loopContext)
        return when (result) {
            null -> ""
            is String -> result
            is Number -> if (result.toDouble() == result.toLong().toDouble()) {
                result.toLong().toString()
            } else {
                result.toString()
            }
            is Boolean -> result.toString()
            else -> result.toString()
        }
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

    private fun parsePath(path: String): List<PathSegment> {
        val segments = mutableListOf<PathSegment>()
        val current = StringBuilder()

        var i = 0
        while (i < path.length) {
            when (val char = path[i]) {
                '.' -> {
                    if (current.isNotEmpty()) {
                        segments.add(parseSegment(current.toString()))
                        current.clear()
                    }
                    i++
                }
                '[' -> {
                    if (current.isNotEmpty()) {
                        segments.add(parseSegment(current.toString()))
                        current.clear()
                    }
                    // Find closing bracket
                    val closeBracket = path.indexOf(']', i)
                    if (closeBracket == -1) {
                        // Invalid syntax, treat rest as property name
                        current.append(path.substring(i))
                        break
                    }
                    val indexStr = path.substring(i + 1, closeBracket).trim()
                    segments.add(parseSegment(indexStr))
                    i = closeBracket + 1
                }
                else -> {
                    current.append(char)
                    i++
                }
            }
        }

        if (current.isNotEmpty()) {
            segments.add(parseSegment(current.toString()))
        }

        return segments
    }

    private fun parseSegment(segment: String): PathSegment {
        // Try to parse as integer index
        val index = segment.toIntOrNull()
        return if (index != null) {
            PathSegment.Index(index)
        } else {
            PathSegment.Property(segment)
        }
    }

    private fun resolvePath(segments: List<PathSegment>, context: Any?): Any? {
        var current: Any? = context

        for (segment in segments) {
            if (current == null) return null

            current = when (segment) {
                is PathSegment.Property -> resolveProperty(current, segment.name)
                is PathSegment.Index -> resolveIndex(current, segment.index)
            }
        }

        return current
    }

    private fun resolveProperty(obj: Any, name: String): Any? = when (obj) {
        is Map<*, *> -> obj[name]
        else -> {
            // Try to get property via reflection (for data classes, etc.)
            try {
                val property = obj::class.members.find { it.name == name }
                property?.call(obj)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun resolveIndex(obj: Any, index: Int): Any? = when (obj) {
        is List<*> -> obj.getOrNull(index)
        is Array<*> -> obj.getOrNull(index)
        else -> null
    }

    private fun isTruthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is String -> value.isNotEmpty()
        is Collection<*> -> value.isNotEmpty()
        is Array<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> true
    }

    private sealed class PathSegment {
        data class Property(val name: String) : PathSegment()
        data class Index(val index: Int) : PathSegment()
    }
}

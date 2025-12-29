package app.epistola.generation

import app.epistola.generation.expression.ExpressionEvaluator as ExpressionEvaluatorInterface

/**
 * Simple path-based expression evaluator.
 *
 * This is a lightweight evaluator that only supports path traversal:
 * - Simple paths: `customer.name`
 * - Array access: `items.0.name` or `items[0].name`
 * - Nested paths: `order.customer.address.city`
 *
 * Use this for simple variable substitution where you don't need
 * the full power of JSONata or JavaScript.
 *
 * Advantages:
 * - Very fast (no parsing overhead)
 * - Safe (no code execution, only data access)
 * - Predictable behavior
 *
 * Limitations:
 * - No operations (filtering, mapping, aggregation)
 * - No string manipulation
 * - No conditionals
 */
class SimplePathEvaluator : ExpressionEvaluatorInterface {
    override fun evaluate(
        expression: String,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?>,
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

    private sealed class PathSegment {
        data class Property(val name: String) : PathSegment()
        data class Index(val index: Int) : PathSegment()
    }
}

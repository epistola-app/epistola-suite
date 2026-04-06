package app.epistola.generation.expression

import app.epistola.generation.DEFAULT_RENDER_TIMEZONE
import com.dashjoin.jsonata.Jsonata.Fn2
import com.dashjoin.jsonata.Jsonata.jsonata
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale

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
 * Custom functions:
 * - `$formatDate(value, pattern)`: Format an ISO date or datetime string using a
 *   [DateTimeFormatter] pattern (e.g., "dd-MM-yyyy HH:mm"). Supports plain dates
 *   (`2024-01-15`), local datetimes (`2024-01-15T14:30:00`), offset datetimes
 *   (`2024-01-15T14:30:00+02:00`), and UTC (`2024-01-15T14:30:00Z`).
 *   Datetimes are converted to the configured [timeZone] before formatting.
 *   Returns the original value on parse failure.
 *
 * @see <a href="https://jsonata.org">JSONata Documentation</a>
 */
class JsonataEvaluator(
    private val locale: Locale = Locale.ENGLISH,
    private val timeZone: ZoneId = DEFAULT_RENDER_TIMEZONE,
) : ExpressionEvaluator {
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

            // Register custom functions
            registerCustomFunctions(frame)

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

    private fun registerCustomFunctions(frame: com.dashjoin.jsonata.Jsonata.Frame) {
        frame.bind(
            "formatDate",
            Fn2 { value: Any?, pattern: Any? ->
                if (value == null) return@Fn2 null
                formatDate(value.toString(), pattern?.toString() ?: "")
            },
        )
    }

    private fun formatDate(value: String, pattern: String): String {
        val temporal = parseDateTime(value) ?: return value
        return try {
            val formatter = DateTimeFormatter.ofPattern(pattern, locale)
            formatter.format(temporal)
        } catch (_: Exception) {
            value
        }
    }

    /**
     * Parse an ISO date or datetime string into a [TemporalAccessor].
     *
     * Tries in order: [OffsetDateTime] (includes Z), [LocalDateTime], [LocalDate].
     * Offset/zoned datetimes are converted to [timeZone] so time-of-day tokens
     * reflect the configured timezone. Local datetimes are assumed to already be
     * in the target timezone.
     */
    private fun parseDateTime(value: String): TemporalAccessor? = tryParse { OffsetDateTime.parse(value).atZoneSameInstant(timeZone) }
        ?: tryParse { LocalDateTime.parse(value).atZone(timeZone) }
        ?: tryParse { LocalDate.parse(value) }

    private inline fun <T> tryParse(block: () -> T): T? = try {
        block()
    } catch (_: Exception) {
        null
    }
}

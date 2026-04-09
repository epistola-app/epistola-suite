package app.epistola.generation.expression

import app.epistola.generation.DEFAULT_RENDER_TIMEZONE
import com.dashjoin.jsonata.Jsonata.Fn2
import com.dashjoin.jsonata.Jsonata.jsonata
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
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
 * - `$formatNumber(value, pattern)`: Format a numeric value using a [DecimalFormat]
 *   pattern (e.g., "#,##0.00", "0%"). Accepts numbers and numeric strings.
 *   Uses the configured [locale] for grouping/decimal separators.
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
        frame.bind(
            "formatNumber",
            Fn2 { value: Any?, pattern: Any? ->
                if (value == null) return@Fn2 null
                formatNumber(value, pattern?.toString() ?: "")
            },
        )
    }

    private fun formatNumber(value: Any, pattern: String): String {
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: return value
            else -> return value.toString()
        }
        return try {
            val commaNotation = isCommaNotation(pattern)
            val canonicalPattern = if (commaNotation) swapSeparators(pattern) else pattern
            val symbols = DecimalFormatSymbols(locale)
            if (commaNotation) {
                symbols.decimalSeparator = ','
                symbols.groupingSeparator = '.'
            }
            DecimalFormat(canonicalPattern, symbols).format(number)
        } catch (_: Exception) {
            value.toString()
        }
    }

    /**
     * Detect comma notation: the pattern uses `,` as decimal separator and `.` as grouping.
     * The last separator character in the pattern determines the decimal separator.
     * When only one type of separator is present, a heuristic based on the number of
     * trailing digit tokens distinguishes grouping (exactly 3) from decimal.
     */
    private fun isCommaNotation(pattern: String): Boolean {
        val lastComma = pattern.lastIndexOf(',')
        val lastDot = pattern.lastIndexOf('.')
        if (lastComma < 0 && lastDot < 0) return false
        if (lastComma < 0) {
            // Only dots — grouping has exactly 3 digit chars after last dot
            val afterDot = pattern.substring(lastDot + 1).count { it == '0' || it == '#' }
            return afterDot == 3
        }
        if (lastDot < 0) {
            // Only commas — grouping has exactly 3 digit chars after last comma
            val afterComma = pattern.substring(lastComma + 1).count { it == '0' || it == '#' }
            return afterComma != 3
        }
        // Both present — the one that comes last is the decimal separator
        return lastComma > lastDot
    }

    /** Swap `,` and `.` in a pattern to convert between comma and point notation. */
    private fun swapSeparators(pattern: String): String = buildString(pattern.length) {
        for (c in pattern) {
            append(
                when (c) {
                    ',' -> '.'
                    '.' -> ','
                    else -> c
                },
            )
        }
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

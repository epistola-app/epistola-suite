package app.epistola.generation.expression

import app.epistola.generation.DEFAULT_LOCALE
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
 * - `$formatLocaleNumber(value, picture)`: Format a number using a Java
 *   [DecimalFormat] picture (e.g., `#,##0.00`) with locale-aware decimal and
 *   grouping separators. Deliberately separate from JSONata's built-in
 *   `$formatNumber` (which is locale-agnostic by XPath 3 spec) so existing
 *   call sites of `$formatNumber` keep their W3C-defined behaviour.
 *   Returns the original value on parse failure.
 *
 * @see <a href="https://jsonata.org">JSONata Documentation</a>
 */
class JsonataEvaluator(
    private val locale: Locale = DEFAULT_LOCALE,
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
            "formatLocaleNumber",
            Fn2 { value: Any?, picture: Any? ->
                if (value == null) return@Fn2 null
                formatLocaleNumber(value, picture?.toString() ?: "")
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
     * Format [value] using a [DecimalFormat] [picture] (e.g. `#,##0.00`) with
     * the evaluator's [locale]-aware separators ([DecimalFormatSymbols]).
     *
     * Java's `DecimalFormat` picture syntax overlaps XPath 3 / XSLT for the
     * cases templates typically use: `0` / `#` digits, `,` grouping, `.`
     * decimal, `;` negative subpattern, `E` scientific, `%` percent
     * (auto-multiplies by 100), `‰` per-mille (auto-multiplies by 1000).
     * The locale supplies the *characters* for grouping/decimal — the
     * picture supplies the *shape* — so `'#,##0.00'` renders as
     * `"1,234.56"` in en-US and `"1.234,56"` in nl-NL with the same picture.
     *
     * Falls back to the original value on parse failure (matches the spirit
     * of [formatDate]) so a broken template never breaks the document.
     */
    private fun formatLocaleNumber(value: Any, picture: String): String {
        val number = (value as? Number)?.toDouble() ?: value.toString().toDoubleOrNull() ?: return value.toString()
        return try {
            val formatter = DecimalFormat(picture, DecimalFormatSymbols(locale))
            formatter.format(number)
        } catch (_: Exception) {
            value.toString()
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

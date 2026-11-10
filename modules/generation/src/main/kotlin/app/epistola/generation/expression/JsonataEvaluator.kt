// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.expression

import app.epistola.generation.DEFAULT_LOCALE
import app.epistola.generation.DEFAULT_RENDER_TIMEZONE
import com.dashjoin.jsonata.JException
import com.dashjoin.jsonata.Jsonata.Fn2
import com.dashjoin.jsonata.Jsonata.JFunction
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

            // Bounds between evaluation steps: a time budget and a recursion depth. A single
            // built-in call runs to completion inside one step, so the built-ins that could run
            // away on their own are bounded where they are bound (registerCustomFunctions).
            val frame = jsonataExpr.createFrame()
            frame.setRuntimeBounds(TIMEOUT_MS, MAX_DEPTH)

            // Register custom functions
            registerCustomFunctions(frame)

            // Bind loop context variables at the top level for easy access
            loopContext.forEach { (key, value) ->
                frame.bind(key, value)
            }

            jsonataExpr.evaluate(context, frame)
        } catch (e: ExpressionLimitException) {
            throw e.forExpression(trimmed)
        } catch (e: JException) {
            // A bound the library enforces is a limit too; any other JSONata error renders as empty.
            if (e.isRuntimeBound()) throw ExpressionLimitException(e.message ?: "it ran past its limits").forExpression(trimmed)
            (e.cause as? ExpressionLimitException)?.let { throw it.forExpression(trimmed) }
            null
        } catch (_: StackOverflowError) {
            // Nested too deeply to parse or evaluate: the stack unwinds here, the thread survives.
            throw ExpressionLimitException("it is nested too deeply").forExpression(trimmed)
        } catch (e: Exception) {
            (e.cause as? ExpressionLimitException)?.let { throw it.forExpression(trimmed) }
            null
        }
    }

    private fun JException.isRuntimeBound(): Boolean {
        val text = message.orEmpty()
        // The library prefixes its messages ("JSonataException …"), so their content is matched.
        return LIBRARY_TIMEOUT in text || LIBRARY_DEPTH in text
    }

    private fun registerCustomFunctions(frame: com.dashjoin.jsonata.Jsonata.Frame) {
        // Shadows the built-in, which is quadratic in its width and takes any width: one
        // `$pad('', 50000000)` ran for hours. Same result as JSONata's up to MAX_PAD_WIDTH.
        // A JFunction with JSONata's own signature: Frame.bind has no three-argument lambda overload.
        frame.bind("pad", JFunction({ _, args -> pad(args.getOrNull(0), args.getOrNull(1), args.getOrNull(2)) }, PAD_SIGNATURE))
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

    /**
     * JSONata's `$pad`: [value] padded with [padding] (a space by default, repeated and cut to fit)
     * to [width] characters, on the right when positive and on the left when negative; unchanged
     * when already as wide. Counted in code points, as JSONata counts.
     */
    private fun pad(value: Any?, width: Any?, padding: Any?): String? {
        if (value == null) return null
        val text = value as? String ?: throw JException("T0410", 0)
        val requested = (width as? Number)?.toDouble() ?: throw JException("T0410", 1)
        if (requested.isNaN() || kotlin.math.abs(requested) > MAX_PAD_WIDTH) {
            throw ExpressionLimitException("\$pad pads to at most $MAX_PAD_WIDTH characters, not ${requested.toLong()}")
        }
        val target = kotlin.math.abs(requested.toInt())
        val fill = (padding as? String)?.takeIf { it.isNotEmpty() } ?: " "
        val missing = target - text.codePointCount(0, text.length)
        if (missing <= 0) return text
        val fillPoints = fill.codePoints().toArray()
        val pad = StringBuilder()
        for (i in 0 until missing) pad.appendCodePoint(fillPoints[i % fillPoints.size])
        return if (requested < 0) "$pad$text" else "$text$pad"
    }

    private inline fun <T> tryParse(block: () -> T): T? = try {
        block()
    } catch (_: Exception) {
        null
    }

    companion object {
        /** Time budget of one expression, checked between evaluation steps. */
        const val TIMEOUT_MS = 5_000L

        /** Deepest recursion one expression may reach. */
        const val MAX_DEPTH = 100

        /** Widest `$pad` result: padding is for references and columns, tens of characters. */
        const val MAX_PAD_WIDTH = 100

        /** JSONata's signature of `$pad`: a string (or the context), a width, an optional padding. */
        private const val PAD_SIGNATURE = "<s-ns?:s>"

        // The messages the library's own bounds throw with (com.dashjoin.jsonata.Timebox).
        private const val LIBRARY_TIMEOUT = "Expression evaluation timeout"
        private const val LIBRARY_DEPTH = "Stack overflow error"
    }
}

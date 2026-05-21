package app.epistola.generation.expression

import app.epistola.generation.DEFAULT_RENDER_TIMEZONE
import app.epistola.generation.SimplePathEvaluator
import app.epistola.template.model.Expression
import app.epistola.template.model.ExpressionLanguage
import java.time.ZoneId
import java.util.Locale

/**
 * Dispatches expression evaluation to the appropriate evaluator based on the expression language.
 *
 * This is the main entry point for expression evaluation in the generation module.
 * It delegates to JSONata, JavaScript, or SimplePath evaluators based on the Expression model.
 *
 * Fields are intentionally public `val` so a renderer can build a locale-scoped
 * variant via [forLocale] without paying the price of constructing a fresh
 * GraalJS engine for every PDF.
 */
class CompositeExpressionEvaluator(
    val jsonataEvaluator: ExpressionEvaluator = JsonataEvaluator(),
    val javaScriptEvaluator: ExpressionEvaluator = JavaScriptEvaluator(),
    val simplePathEvaluator: ExpressionEvaluator = SimplePathEvaluator(),
) {
    /**
     * Render-scoped copy with a fresh [JsonataEvaluator] bound to [locale]
     * (and [timeZone]). The other evaluators are reused — only JSONata cares
     * about locale today (via `$formatDate`), and re-creating [JavaScriptEvaluator]
     * would re-init the GraalJS engine on every render.
     *
     * Returns `this` if [locale] matches the default — no allocation for the
     * untouched English path.
     */
    fun forLocale(locale: Locale, timeZone: ZoneId = DEFAULT_RENDER_TIMEZONE): CompositeExpressionEvaluator {
        if (locale == Locale.ENGLISH && timeZone == DEFAULT_RENDER_TIMEZONE) return this
        return CompositeExpressionEvaluator(
            jsonataEvaluator = JsonataEvaluator(locale = locale, timeZone = timeZone),
            javaScriptEvaluator = this.javaScriptEvaluator,
            simplePathEvaluator = this.simplePathEvaluator,
        )
    }

    /**
     * Evaluates an Expression model against the provided data context.
     */
    fun evaluate(
        expression: Expression,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?> = emptyMap(),
    ): Any? {
        val evaluator = getEvaluator(expression.language)
        return evaluator.evaluate(expression.raw, data, loopContext)
    }

    /**
     * Evaluates an expression string with explicit language.
     */
    fun evaluate(
        raw: String,
        language: ExpressionLanguage,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?> = emptyMap(),
    ): Any? {
        val evaluator = getEvaluator(language)
        return evaluator.evaluate(raw, data, loopContext)
    }

    /**
     * Evaluates an Expression and converts the result to a String.
     */
    fun evaluateToString(
        expression: Expression,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?> = emptyMap(),
    ): String {
        val evaluator = getEvaluator(expression.language)
        return evaluator.evaluateToString(expression.raw, data, loopContext)
    }

    /**
     * Evaluates a condition Expression and returns a boolean.
     */
    fun evaluateCondition(
        expression: Expression,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?> = emptyMap(),
    ): Boolean {
        val evaluator = getEvaluator(expression.language)
        return evaluator.evaluateCondition(expression.raw, data, loopContext)
    }

    /**
     * Evaluates an Expression that should return an iterable collection.
     */
    fun evaluateIterable(
        expression: Expression,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?> = emptyMap(),
    ): List<Any?> {
        val evaluator = getEvaluator(expression.language)
        return evaluator.evaluateIterable(expression.raw, data, loopContext)
    }

    /**
     * Processes a string containing embedded expressions like "Hello {{name}}!".
     *
     * Note: All embedded expressions in a single template string use the same language.
     * This is intended for simple variable substitution; complex templates should use
     * individual Expression blocks.
     */
    fun processTemplate(
        template: String,
        language: ExpressionLanguage,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?> = emptyMap(),
    ): String {
        val evaluator = getEvaluator(language)
        return evaluator.processTemplate(template, data, loopContext)
    }

    private fun getEvaluator(language: ExpressionLanguage): ExpressionEvaluator = when (language) {
        ExpressionLanguage.jsonata -> jsonataEvaluator
        ExpressionLanguage.javascript -> javaScriptEvaluator
        ExpressionLanguage.simple_path -> simplePathEvaluator
    }
}

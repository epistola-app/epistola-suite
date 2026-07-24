// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.expression

import app.epistola.generation.RenderCulture
import app.epistola.generation.SimplePathEvaluator
import app.epistola.template.model.Expression
import app.epistola.template.model.ExpressionLanguage

/**
 * Dispatches expression evaluation to the appropriate evaluator based on the expression language.
 *
 * This is the main entry point for expression evaluation in the generation module.
 * It delegates to JSONata, JavaScript, or SimplePath evaluators based on the Expression model.
 *
 * Fields are intentionally public `val` so a renderer can build a culture-scoped
 * variant via [forCulture] without paying the price of constructing a fresh
 * GraalJS engine for every PDF.
 */
class CompositeExpressionEvaluator(
    val jsonataEvaluator: ExpressionEvaluator = JsonataEvaluator(),
    val javaScriptEvaluator: ExpressionEvaluator = JavaScriptEvaluator(),
    val simplePathEvaluator: ExpressionEvaluator = SimplePathEvaluator(),
) {
    /**
     * Render-scoped copy with a fresh [JsonataEvaluator] bound to [culture]'s
     * locale and timezone. The other evaluators are reused — only JSONata cares
     * about culture today (via `$formatDate` / `$formatLocaleNumber`), and
     * re-creating [JavaScriptEvaluator] would re-init the GraalJS engine on
     * every render.
     *
     * Returns `this` when [culture] is [RenderCulture.DEFAULT] — no allocation
     * for the untouched default install.
     */
    fun forCulture(culture: RenderCulture): CompositeExpressionEvaluator {
        if (culture == RenderCulture.DEFAULT) return this
        return CompositeExpressionEvaluator(
            jsonataEvaluator = JsonataEvaluator(locale = culture.locale, timeZone = culture.timeZone),
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

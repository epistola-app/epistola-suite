package app.epistola.generation.expression

import app.epistola.template.model.Expression
import app.epistola.template.model.ExpressionLanguage

/**
 * Dispatches expression evaluation to the appropriate evaluator based on the expression language.
 *
 * This is the main entry point for expression evaluation in the generation module.
 * It delegates to either JSONata or JavaScript evaluators based on the Expression model.
 */
class CompositeExpressionEvaluator(
    private val jsonataEvaluator: ExpressionEvaluator = JsonataEvaluator(),
    private val javaScriptEvaluator: ExpressionEvaluator = JavaScriptEvaluator(),
) {
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
        ExpressionLanguage.Jsonata -> jsonataEvaluator
        ExpressionLanguage.JavaScript -> javaScriptEvaluator
    }
}

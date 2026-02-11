package app.epistola.generation.pdf

import app.epistola.generation.TipTapConverter
import app.epistola.generation.expression.CompositeExpressionEvaluator
import app.epistola.template.model.DocumentStyles
import app.epistola.template.model.ExpressionLanguage

/**
 * Context passed to block renderers during PDF generation.
 */
data class RenderContext(
    val data: Map<String, Any?>,
    val loopContext: Map<String, Any?> = emptyMap(),
    val documentStyles: DocumentStyles? = null,
    val expressionEvaluator: CompositeExpressionEvaluator,
    val tipTapConverter: TipTapConverter,
    /** Default language for embedded expressions in text (e.g., "Hello {{name}}!") */
    val defaultExpressionLanguage: ExpressionLanguage = ExpressionLanguage.Jsonata,
    /** Font cache scoped to this document */
    val fontCache: FontCache,
    /** Block style presets from theme (named style collections like CSS classes) */
    val blockStylePresets: Map<String, Map<String, Any>> = emptyMap(),
    /** Inheritable styles resolved from document/ancestor chain */
    val inheritedStyles: Map<String, Any> = emptyMap(),
)

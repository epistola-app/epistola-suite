package app.epistola.generation.pdf

import app.epistola.generation.ExpressionEvaluator
import app.epistola.generation.TipTapConverter
import app.epistola.template.model.DocumentStyles

/**
 * Context passed to block renderers during PDF generation.
 */
data class RenderContext(
    val data: Map<String, Any?>,
    val loopContext: Map<String, Any?> = emptyMap(),
    val documentStyles: DocumentStyles? = null,
    val expressionEvaluator: ExpressionEvaluator,
    val tipTapConverter: TipTapConverter,
)

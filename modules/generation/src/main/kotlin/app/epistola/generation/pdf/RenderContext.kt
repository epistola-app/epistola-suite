package app.epistola.generation.pdf

import app.epistola.generation.TipTapConverter
import app.epistola.generation.expression.CompositeExpressionEvaluator
import app.epistola.template.model.DocumentStyles
import app.epistola.template.model.ExpressionLanguage
import app.epistola.template.model.TemplateDocument

/**
 * Context passed to node renderers during PDF generation.
 */
data class RenderContext(
    val data: Map<String, Any?>,
    val loopContext: Map<String, Any?> = emptyMap(),
    val documentStyles: DocumentStyles? = null,
    val expressionEvaluator: CompositeExpressionEvaluator,
    val tipTapConverter: TipTapConverter,
    /** Default language for embedded expressions in text (e.g., "Hello {{name}}!") */
    val defaultExpressionLanguage: ExpressionLanguage = ExpressionLanguage.jsonata,
    /** Font cache scoped to this document */
    val fontCache: FontCache,
    /** Block style presets from theme (named style collections like CSS classes) */
    val blockStylePresets: Map<String, Map<String, Any>> = emptyMap(),
    /** The template document being rendered (for node/slot lookups) */
    val document: TemplateDocument,
    /** Optional asset resolver for loading image content during rendering */
    val assetResolver: AssetResolver? = null,
    /** System parameters injected by the rendering engine (e.g., page number in headers/footers). */
    val systemParams: Map<String, Any?> = emptyMap(),
) {
    /**
     * Data map with system parameters merged under the `sys` key.
     * Returns the original [data] map when no system parameters are set.
     */
    val effectiveData: Map<String, Any?>
        get() = if (systemParams.isEmpty()) data else data + mapOf("sys" to systemParams)

    /**
     * Returns a copy of this context with page-scoped system parameters injected.
     */
    fun withPageParams(pageNumber: Int): RenderContext = copy(
        systemParams = systemParams + mapOf("page" to mapOf("number" to pageNumber)),
    )
}

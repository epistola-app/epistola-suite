package app.epistola.generation.html

import app.epistola.generation.AssetResolver
import app.epistola.generation.SystemParameterRegistry
import app.epistola.generation.expression.CompositeExpressionEvaluator
import app.epistola.generation.pdf.RenderingDefaults
import app.epistola.template.model.DocumentStyles
import app.epistola.template.model.ExpressionLanguage
import app.epistola.template.model.TemplateDocument

/**
 * Context passed to node renderers during HTML generation.
 */
data class HtmlRenderContext(
    val data: Map<String, Any?>,
    val loopContext: Map<String, Any?> = emptyMap(),
    val documentStyles: DocumentStyles? = null,
    val expressionEvaluator: CompositeExpressionEvaluator,
    val tipTapHtmlConverter: TipTapHtmlConverter,
    val defaultExpressionLanguage: ExpressionLanguage = ExpressionLanguage.jsonata,
    val blockStylePresets: Map<String, Map<String, Any>> = emptyMap(),
    val document: TemplateDocument,
    val assetResolver: AssetResolver? = null,
    val renderingDefaults: RenderingDefaults = RenderingDefaults.CURRENT,
    val systemParams: Map<String, Any?> = emptyMap(),
) {
    val effectiveData: Map<String, Any?>
        get() = if (systemParams.isEmpty()) data else data + mapOf("sys" to systemParams)

    fun withPageParams(pageNumber: Int): HtmlRenderContext = copy(
        systemParams = systemParams + SystemParameterRegistry.buildPageParams(pageNumber),
    )
}

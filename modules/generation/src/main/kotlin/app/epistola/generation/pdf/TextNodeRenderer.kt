package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.IElement

/**
 * Renders a "text" node to iText elements.
 *
 * Props:
 * - `content`: TipTap JSON content (Map<String, Any>)
 */
class TextNodeRenderer : NodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: RenderContext,
        registry: NodeRendererRegistry,
    ): List<IElement> {
        val div = Div()

        // Apply node styles with theme preset resolution
        StyleApplicator.applyStylesWithPreset(
            div,
            node.styles?.filterNonNullValues(),
            node.stylePreset,
            context.blockStylePresets,
            context.documentStyles,
            context.fontCache,
            context.renderingDefaults.componentDefaults("text"),
            context.renderingDefaults.baseFontSizePt,
            context.spacingUnit,
        )

        // Resolve the full style cascade, then parse size values to points.
        // TipTapConverter receives pre-resolved values — it doesn't parse units.
        val rawStyles = buildMap<String, Any> {
            context.documentStyles?.filterKeys { it in StyleApplicator.INHERITABLE_KEYS }?.let { putAll(it) }
            StyleApplicator.resolveBlockStyles(context.blockStylePresets, node.stylePreset, node.styles?.filterNonNullValues())
                ?.let { putAll(it) }
        }
        val resolvedStyles = buildMap<String, Any> {
            rawStyles["lineHeight"]?.toString()?.toFloatOrNull()?.let {
                put("lineHeight", it)
            }
        }

        // Convert TipTap content to iText elements
        @Suppress("UNCHECKED_CAST")
        val content = node.props?.get("content") as? Map<String, Any>
        val elements = context.tipTapConverter.convert(content, context.effectiveData, context.loopContext, context.fontCache, resolvedStyles)

        for (element in elements) {
            div.add(element)
        }

        return listOf(div)
    }
}

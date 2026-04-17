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

        // Resolve the full style cascade for the text node — passed to TipTapConverter
        // so it can apply properties like lineHeight, textIndent, etc. to paragraphs.
        val resolvedStyles = buildMap<String, Any> {
            // Inheritable document styles (lowest priority)
            context.documentStyles?.filterKeys { it in StyleApplicator.INHERITABLE_KEYS }?.let { putAll(it) }
            // Preset + inline styles (higher priority)
            StyleApplicator.resolveBlockStyles(context.blockStylePresets, node.stylePreset, node.styles?.filterNonNullValues())
                ?.let { putAll(it) }
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

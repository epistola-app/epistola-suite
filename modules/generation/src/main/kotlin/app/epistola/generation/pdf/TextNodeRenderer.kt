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
        )

        // Convert TipTap content to iText elements
        @Suppress("UNCHECKED_CAST")
        val content = node.props?.get("content") as? Map<String, Any>

        // Extract base font size from node styles for typography scale calculations
        val baseFontSizePt = node.styles?.get("fontSize")?.toString()?.let {
            parseFontSize(it, context.renderingDefaults.baseFontSizePt)
        } ?: context.renderingDefaults.baseFontSizePt

        val elements = context.tipTapConverter.convert(
            content,
            context.effectiveData,
            context.loopContext,
            context.fontCache,
            context.documentStyles,
            baseFontSizePt,
        )

        for (element in elements) {
            div.add(element)
        }

        return listOf(div)
    }

    /**
     * Parse a font size string to points.
     * Supports: pt (direct), px (converted), em/rem (relative to base)
     */
    private fun parseFontSize(fontSize: String, baseFontSizePt: Float): Float? = when {
        fontSize.endsWith("pt") -> fontSize.removeSuffix("pt").toFloatOrNull()
        fontSize.endsWith("px") -> fontSize.removeSuffix("px").toFloatOrNull()?.times(0.75f)
        fontSize.endsWith("em") -> fontSize.removeSuffix("em").toFloatOrNull()?.times(baseFontSizePt)
        fontSize.endsWith("rem") -> fontSize.removeSuffix("rem").toFloatOrNull()?.times(baseFontSizePt)
        else -> fontSize.toFloatOrNull()
    }
}

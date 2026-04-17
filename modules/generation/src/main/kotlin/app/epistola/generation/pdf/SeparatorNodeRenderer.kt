package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.IElement

/**
 * Renders a "separator" node as a zero-height div with a bottom border,
 * producing a horizontal line in the PDF output.
 */
class SeparatorNodeRenderer : NodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: RenderContext,
        registry: NodeRendererRegistry,
    ): List<IElement> {
        val div = Div()
        StyleApplicator.applyStylesWithPreset(
            div,
            node.styles?.filterNonNullValues(),
            node.stylePreset,
            context.blockStylePresets,
            context.documentStyles,
            context.fontCache,
            context.renderingDefaults.componentDefaults("separator"),
            context.renderingDefaults.baseFontSizePt,
            context.spacingUnit,
        )
        div.setHeight(0f)
        return listOf(div)
    }
}

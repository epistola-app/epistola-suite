package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.IElement

/**
 * Renders a "pagebreak" node to force content onto a new page.
 */
class PageBreakNodeRenderer : NodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: RenderContext,
        registry: NodeRendererRegistry,
    ): List<IElement> = listOf(AreaBreak())
}

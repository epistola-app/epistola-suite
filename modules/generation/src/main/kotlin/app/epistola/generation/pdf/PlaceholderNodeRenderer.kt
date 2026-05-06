package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.layout.element.IElement

/**
 * Renders a `placeholder` node.
 *
 * A placeholder owns two slots:
 *   - `default` — the stencil author's content; shown when no override exists.
 *   - `fill`    — the embedding template's override; takes precedence when non-empty.
 *
 * The renderer dispatches by slot name (not position) and falls back to default
 * when the fill slot is empty.
 */
class PlaceholderNodeRenderer : NodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: RenderContext,
        registry: NodeRendererRegistry,
    ): List<IElement> {
        val fillSlotId = node.slots.firstOrNull { document.slots[it]?.name == "fill" }
        val defaultSlotId = node.slots.firstOrNull { document.slots[it]?.name == "default" }
        val fillSlot = fillSlotId?.let { document.slots[it] }

        val targetSlotId = if (fillSlot != null && fillSlot.children.isNotEmpty()) {
            fillSlotId
        } else {
            defaultSlotId
        } ?: return emptyList()

        return registry.renderSlot(targetSlotId, document, context)
    }
}

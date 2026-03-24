package app.epistola.generation.html

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument

/**
 * Interface for rendering template nodes to HTML strings.
 */
interface HtmlNodeRenderer {
    fun render(
        node: Node,
        document: TemplateDocument,
        context: HtmlRenderContext,
        registry: HtmlNodeRendererRegistry,
    ): String
}

/**
 * Registry of HTML node renderers by node type.
 */
class HtmlNodeRendererRegistry(
    initialRenderers: Map<String, HtmlNodeRenderer> = emptyMap(),
) {
    private val renderers = initialRenderers.toMutableMap()

    fun renderNode(
        nodeId: String,
        document: TemplateDocument,
        context: HtmlRenderContext,
    ): String {
        val node = document.nodes[nodeId] ?: return ""
        val renderer = renderers[node.type] ?: return ""
        return renderer.render(node, document, context, this)
    }

    fun renderSlot(
        slotId: String,
        document: TemplateDocument,
        context: HtmlRenderContext,
    ): String {
        val slot = document.slots[slotId] ?: return ""
        return slot.children.joinToString("") { childNodeId -> renderNode(childNodeId, document, context) }
    }

    fun renderSlots(
        node: Node,
        document: TemplateDocument,
        context: HtmlRenderContext,
    ): String = node.slots.joinToString("") { slotId -> renderSlot(slotId, document, context) }
}

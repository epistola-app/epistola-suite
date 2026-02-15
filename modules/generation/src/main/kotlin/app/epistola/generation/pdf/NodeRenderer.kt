package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.layout.element.IElement

/**
 * Interface for rendering template nodes to iText PDF elements.
 *
 * Each implementation handles a specific node type (e.g., "text", "container", "columns").
 * Traversal of the node/slot graph is delegated to [NodeRendererRegistry].
 */
interface NodeRenderer {
    /**
     * Renders a node to a list of iText elements.
     *
     * @param node The node to render
     * @param document The full template document (for looking up slots/other nodes)
     * @param context The render context containing data, evaluator, etc.
     * @param registry The node renderer registry for recursive rendering via slots
     * @return List of iText elements (can be IBlockElement, AreaBreak, or Image)
     */
    fun render(
        node: Node,
        document: TemplateDocument,
        context: RenderContext,
        registry: NodeRendererRegistry,
    ): List<IElement>
}

/**
 * Registry of node renderers by node type.
 *
 * Provides the central traversal logic for the node/slot graph:
 * - [renderNode] looks up a node by ID and dispatches to the correct [NodeRenderer]
 * - [renderSlot] looks up a slot by ID and renders each child node in order
 */
class NodeRendererRegistry(
    initialRenderers: Map<String, NodeRenderer> = emptyMap(),
) {
    private val renderers = initialRenderers.toMutableMap()

    fun register(nodeType: String, renderer: NodeRenderer) {
        renderers[nodeType] = renderer
    }

    /**
     * Looks up a node by ID from the document, finds the matching renderer by node type,
     * and renders it.
     *
     * @param nodeId The ID of the node to render
     * @param document The template document containing the node/slot maps
     * @param context The render context
     * @return The rendered iText elements, or empty list if the node or renderer is not found
     */
    fun renderNode(
        nodeId: String,
        document: TemplateDocument,
        context: RenderContext,
    ): List<IElement> {
        val node = document.nodes[nodeId] ?: return emptyList()
        val renderer = renderers[node.type] ?: return emptyList()
        return renderer.render(node, document, context, this)
    }

    /**
     * Looks up a slot by ID from the document, then renders each child node in order.
     *
     * @param slotId The ID of the slot to render
     * @param document The template document containing the node/slot maps
     * @param context The render context
     * @return The rendered iText elements from all children in the slot
     */
    fun renderSlot(
        slotId: String,
        document: TemplateDocument,
        context: RenderContext,
    ): List<IElement> {
        val slot = document.slots[slotId] ?: return emptyList()
        return slot.children.flatMap { childNodeId -> renderNode(childNodeId, document, context) }
    }

    /**
     * Renders all slots owned by a node, concatenating the results.
     *
     * @param node The node whose slots to render
     * @param document The template document
     * @param context The render context
     * @return The rendered iText elements from all slots
     */
    fun renderSlots(
        node: Node,
        document: TemplateDocument,
        context: RenderContext,
    ): List<IElement> = node.slots.flatMap { slotId -> renderSlot(slotId, document, context) }
}

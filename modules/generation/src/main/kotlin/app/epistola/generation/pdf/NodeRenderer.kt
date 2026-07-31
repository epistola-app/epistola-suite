// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.IBlockElement
import com.itextpdf.layout.element.IElement
import com.itextpdf.layout.element.Image

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
        val node = document.nodes[nodeId]
            ?: throw IllegalStateException("Node '$nodeId' not found in template document")
        val renderer = renderers[node.type]
            ?: throw IllegalStateException("Unknown node type '${node.type}' for node '$nodeId'. Supported types: ${renderers.keys.sorted()}")
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
        val result = mutableListOf<IElement>()
        val pendingGroup = mutableListOf<IElement>()
        var pendingHasFollower = false

        fun flushPending(groupWithNext: Boolean) {
            if (pendingGroup.isEmpty()) return
            if (groupWithNext && pendingGroup.size > 1) {
                result += keepTogether(pendingGroup)
            } else {
                result += pendingGroup
            }
            pendingGroup.clear()
            pendingHasFollower = false
        }

        for (childNodeId in slot.children) {
            val node = document.nodes[childNodeId]
                ?: throw IllegalStateException("Node '$childNodeId' not found in template document")
            val elements = renderNode(childNodeId, document, context)

            // A condition or other structural node that emits nothing is
            // transparent: the previous visible block keeps with the next
            // visible sibling instead.
            if (elements.isEmpty()) continue

            // Never group across an explicit page break, including one emitted
            // by a structural child such as a conditional.
            if (elements.any { it is AreaBreak }) {
                flushPending(groupWithNext = pendingHasFollower)
                result += elements
                continue
            }

            if (pendingGroup.isNotEmpty()) {
                pendingHasFollower = true
                pendingGroup += elements
                if (!keepsWithNext(node, context)) {
                    flushPending(groupWithNext = true)
                }
            } else if (keepsWithNext(node, context)) {
                pendingGroup += elements
            } else {
                result += elements
            }
        }

        // A final keepWithNext has no following rendered sibling, so it has no
        // page-flow effect and is rendered normally.
        flushPending(groupWithNext = pendingHasFollower)
        return result
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

    private fun keepsWithNext(node: Node, context: RenderContext): Boolean {
        val inline = node.styles?.filterNonNullValues()?.get("keepWithNext")
        val preset = node.stylePreset?.let { context.blockStylePresets[it]?.get("keepWithNext") }
        val componentDefault = context.renderingDefaults.componentDefaults(node.type)?.get("keepWithNext")
        val effective = inline ?: preset ?: componentDefault
        return effective == true || effective == "true"
    }

    private fun keepTogether(elements: List<IElement>): Div {
        val group = Div().setKeepTogether(true)
        for (element in elements) {
            when (element) {
                is IBlockElement -> group.add(element)
                is Image -> group.add(element)
                // AreaBreak is excluded by the caller; retain this branch as a
                // defensive fallback for future element implementations.
                is AreaBreak -> group.add(element)
            }
        }
        return group
    }
}

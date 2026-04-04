package app.epistola.suite.stencils.model

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import java.util.UUID

/**
 * Server-side utility for replacing stencil content within a template document.
 * Used by the UpdateStencilInTemplate command to upgrade stencil instances.
 *
 * This is the Kotlin equivalent of the editor's reKeyContent + _replaceContent utilities.
 */
object StencilContentReplacer {

    /**
     * Upgrades all instances of a stencil in the document to use new content.
     *
     * @param document The template document to modify
     * @param stencilId The stencil ID to find and upgrade
     * @param newVersion The new version number to set in props
     * @param newContent The new stencil version's content (TemplateDocument)
     * @return The modified document with upgraded stencil instances, or the original if no changes
     */
    fun upgradeStencilInstances(
        document: TemplateDocument,
        stencilId: String,
        newVersion: Int,
        newContent: TemplateDocument,
    ): TemplateDocument {
        // Find all stencil nodes matching the stencilId
        val stencilNodes = document.nodes.values.filter { node ->
            node.type == "stencil" &&
                (node.props?.get("stencilId") as? String) == stencilId
        }

        if (stencilNodes.isEmpty()) return document

        var nodes = document.nodes.toMutableMap()
        var slots = document.slots.toMutableMap()

        for (stencilNode in stencilNodes) {
            val slotId = stencilNode.slots.firstOrNull() ?: continue
            val slot = slots[slotId] ?: continue

            // Remove old children and their descendants
            val idsToRemove = mutableSetOf<String>()
            val slotIdsToRemove = mutableSetOf<String>()
            for (childId in slot.children) {
                collectDescendants(childId, nodes, slots, idsToRemove, slotIdsToRemove)
            }
            for (id in idsToRemove) nodes.remove(id)
            for (id in slotIdsToRemove) slots.remove(id)

            // Re-key the new content (each instance gets unique IDs)
            val reKeyed = reKeyContent(newContent)

            // Add re-keyed nodes and slots to the document
            for (node in reKeyed.nodes) nodes[node.id] = node
            for (slot2 in reKeyed.slots) slots[slot2.id] = slot2

            // Update the stencil's slot to reference the new children
            slots[slotId] = slot.copy(children = reKeyed.childNodeIds)

            // Update the stencil node's props
            val updatedProps = (stencilNode.props ?: emptyMap()).toMutableMap()
            updatedProps["version"] = newVersion
            nodes[stencilNode.id] = stencilNode.copy(props = updatedProps)
        }

        return document.copy(nodes = nodes, slots = slots)
    }

    private fun collectDescendants(
        nodeId: String,
        nodes: Map<String, Node>,
        slots: Map<String, Slot>,
        nodeIds: MutableSet<String>,
        slotIds: MutableSet<String>,
    ) {
        nodeIds.add(nodeId)
        val node = nodes[nodeId] ?: return
        for (slotId in node.slots) {
            slotIds.add(slotId)
            val slot = slots[slotId] ?: continue
            for (childId in slot.children) {
                collectDescendants(childId, nodes, slots, nodeIds, slotIds)
            }
        }
    }

    /**
     * Re-keys a TemplateDocument's nodes and slots with fresh IDs.
     * Excludes the root node and its slot — only returns the root's children and descendants.
     */
    private fun reKeyContent(content: TemplateDocument): ReKeyResult {
        val nodeIdMap = mutableMapOf<String, String>()
        val slotIdMap = mutableMapOf<String, String>()

        // Generate new IDs
        for (nodeId in content.nodes.keys) {
            nodeIdMap[nodeId] = generateId()
        }
        for (slotId in content.slots.keys) {
            slotIdMap[slotId] = generateId()
        }

        val rootNode = content.nodes[content.root]
        val rootSlotId = rootNode?.slots?.firstOrNull()

        // Re-key nodes (exclude root)
        val nodes = content.nodes.values
            .filter { it.id != content.root }
            .map { node ->
                node.copy(
                    id = nodeIdMap[node.id]!!,
                    slots = node.slots.map { slotIdMap[it]!! },
                )
            }

        // Re-key slots (exclude root's slot)
        val slots = content.slots.values
            .filter { it.id != rootSlotId }
            .map { slot ->
                slot.copy(
                    id = slotIdMap[slot.id]!!,
                    nodeId = nodeIdMap[slot.nodeId]!!,
                    children = slot.children.map { nodeIdMap[it]!! },
                )
            }

        // Root's children (re-keyed)
        val rootSlot = rootSlotId?.let { content.slots[it] }
        val childNodeIds = rootSlot
            ?.children?.map { nodeIdMap[it]!! }
            ?: listOf(nodeIdMap[content.root]!!)

        return ReKeyResult(childNodeIds, nodes, slots)
    }

    private fun generateId(): String = UUID.randomUUID().toString().replace("-", "").take(21)

    private data class ReKeyResult(
        val childNodeIds: List<String>,
        val nodes: List<Node>,
        val slots: List<Slot>,
    )
}

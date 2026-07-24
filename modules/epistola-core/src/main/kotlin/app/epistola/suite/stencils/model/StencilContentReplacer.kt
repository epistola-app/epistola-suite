// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.model

import app.epistola.suite.stencils.PlaceholderNodeKeys
import app.epistola.suite.stencils.StencilNodeKeys
import app.epistola.suite.templates.model.NodeParameterKeys
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * Server-side utility for replacing stencil content within a template document.
 * Used by the UpdateStencilInTemplate command to upgrade stencil instances.
 *
 * This is the Kotlin equivalent of the editor's reKeyContent + _replaceContent utilities.
 */
object StencilContentReplacer {

    /**
     * Result of upgrading stencil instances. Carries the modified document plus
     * a record of any user fills that were dropped because the new stencil
     * version no longer declares the corresponding placeholder. The UI can
     * surface this to the operator so the change is not silently destructive.
     */
    data class UpgradeResult(
        val document: TemplateDocument,
        /** Per-stencil-instance, the fills that could not be preserved. */
        val droppedFills: Map<String, List<DroppedFill>>,
        /**
         * Per-stencil-instance, the parameter bindings whose parameter no longer
         * exists in the new version's schema. Empty when the schema didn't change
         * or every previously-bound name still exists.
         */
        val droppedBindings: Map<String, List<DroppedBinding>> = emptyMap(),
        /**
         * Per-stencil-instance, names of newly-required parameters that have no
         * preserved binding and no `default`. The user must bind these via the
         * inspector before rendering will produce sensible output.
         */
        val unboundRequired: Map<String, List<String>> = emptyMap(),
    )

    data class DroppedFill(val name: String, val contentSummary: String)

    data class DroppedBinding(val name: String, val expression: String)

    /**
     * Upgrades all instances of a stencil in the document to use new content,
     * preserving fills by placeholder name where possible.
     *
     * For every embedded stencil instance:
     *   1. Captures `{ placeholder.name → fill-slot subtree }` from the old children.
     *   2. Removes old children and re-keys the new content into place.
     *   3. For each placeholder in the new content, splices the captured fill
     *      back in (re-keyed) when a name match is found; otherwise the new
     *      version's default content (already in the slot) stays.
     *   4. Captured fills with no matching placeholder name in the new version
     *      are reported in [UpgradeResult.droppedFills] keyed by the stencil
     *      instance's node id.
     */
    fun upgradeStencilInstances(
        document: TemplateDocument,
        stencilId: String,
        newVersion: Int,
        newContent: TemplateDocument,
        newParameterSchema: JsonNode? = null,
    ): UpgradeResult {
        // Find all stencil nodes matching the stencilId
        val stencilNodes = document.nodes.values.filter { node ->
            node.type == StencilNodeKeys.NODE_TYPE &&
                (node.props?.get(StencilNodeKeys.PROP_STENCIL_ID) as? String) == stencilId
        }

        if (stencilNodes.isEmpty()) return UpgradeResult(document, emptyMap())

        val newSchemaProperties = (newParameterSchema as? ObjectNode)?.get("properties") as? ObjectNode
        val newSchemaDeclaredNames = newSchemaProperties?.propertyNames()?.toSet().orEmpty()
        val newSchemaRequired = (newParameterSchema as? ObjectNode)
            ?.get("required")
            ?.let { it as? ArrayNode }
            ?.mapNotNull { it.asString() }
            ?.toSet()
            .orEmpty()

        val nodes = document.nodes.toMutableMap()
        val slots = document.slots.toMutableMap()
        val droppedFillsByInstance = mutableMapOf<String, List<DroppedFill>>()
        val droppedBindingsByInstance = mutableMapOf<String, List<DroppedBinding>>()
        val unboundRequiredByInstance = mutableMapOf<String, List<String>>()

        for (stencilNode in stencilNodes) {
            val slotId = stencilNode.slots.firstOrNull() ?: continue
            val slot = slots[slotId] ?: continue

            // 1. Capture old fills (by placeholder name) before removing.
            val capturedFills = captureFillsByName(slot.children, nodes, slots)

            // 2. Remove old children and their descendants.
            val idsToRemove = mutableSetOf<String>()
            val slotIdsToRemove = mutableSetOf<String>()
            for (childId in slot.children) {
                collectDescendants(childId, nodes, slots, idsToRemove, slotIdsToRemove)
            }
            for (id in idsToRemove) nodes.remove(id)
            for (id in slotIdsToRemove) slots.remove(id)

            // 3. Re-key the new content (each instance gets unique IDs).
            val reKeyed = reKeyContent(newContent)
            for (node in reKeyed.nodes) nodes[node.id] = node
            for (slot2 in reKeyed.slots) slots[slot2.id] = slot2

            // Update the stencil's slot to reference the new children.
            slots[slotId] = slot.copy(children = reKeyed.childNodeIds)

            // Update the stencil node's props — version, refresh schema snapshot,
            // and prune bindings whose parameters no longer exist in the new schema.
            val updatedProps = (stencilNode.props ?: emptyMap()).toMutableMap()
            updatedProps[StencilNodeKeys.PROP_VERSION] = newVersion

            // Refresh the schema snapshot. When no schema is supplied (caller didn't
            // pass one — typically because the new version has no parameters), drop
            // the snapshot entirely so the consumer sees a clean state.
            if (newParameterSchema != null) {
                updatedProps[StencilNodeKeys.PROP_PARAMETER_SCHEMA_SNAPSHOT] = newParameterSchema
            } else {
                updatedProps.remove(StencilNodeKeys.PROP_PARAMETER_SCHEMA_SNAPSHOT)
            }

            // Preserve bindings whose parameter name still exists in the new schema;
            // record dropped bindings (and newly-required-without-binding) for the UI.
            @Suppress("UNCHECKED_CAST")
            val previousBindings = (stencilNode.props?.get(NodeParameterKeys.PROP_PARAMETER_BINDINGS) as? Map<String, Any?>)
                ?.mapNotNull { (k, v) -> if (v is String) k to v else null }
                ?.toMap()
                .orEmpty()

            val preservedBindings = previousBindings.filterKeys { it in newSchemaDeclaredNames }
            val droppedBindings = previousBindings
                .filterKeys { it !in newSchemaDeclaredNames }
                .map { (name, expr) -> DroppedBinding(name = name, expression = expr) }

            if (preservedBindings.isEmpty()) {
                updatedProps.remove(NodeParameterKeys.PROP_PARAMETER_BINDINGS)
            } else {
                updatedProps[NodeParameterKeys.PROP_PARAMETER_BINDINGS] = preservedBindings
            }

            if (droppedBindings.isNotEmpty()) {
                droppedBindingsByInstance[stencilNode.id] = droppedBindings
            }

            // Newly-required params that are unbound and have no default.
            val unbound = newSchemaRequired
                .filter { name ->
                    val hasBinding = preservedBindings.containsKey(name)
                    val hasDefault = (newSchemaProperties?.get(name) as? ObjectNode)?.get("default") != null
                    !hasBinding && !hasDefault
                }
            if (unbound.isNotEmpty()) {
                unboundRequiredByInstance[stencilNode.id] = unbound
            }

            nodes[stencilNode.id] = stencilNode.copy(props = updatedProps)

            // 4. Splice captured fills back into the new placeholders by name.
            // The new placeholder's `default` slot keeps the new stencil's
            // default content untouched; we only replace the `fill` slot.
            val matchedNames = mutableSetOf<String>()
            val newPlaceholders = reKeyed.nodes.filter { it.type == PlaceholderNodeKeys.NODE_TYPE }
            for (newPh in newPlaceholders) {
                val phName = newPh.props?.get(PlaceholderNodeKeys.PROP_NAME) as? String ?: continue
                val capture = capturedFills[phName] ?: continue
                matchedNames.add(phName)

                val phFillSlotId = newPh.slots.firstOrNull {
                    slots[it]?.name == PlaceholderNodeKeys.SLOT_FILL
                }
                    ?: continue
                val phFillSlot = slots[phFillSlotId] ?: continue

                // The new fill slot should be empty (stencil definitions ship
                // with empty fills), but if anything is in it, drop it before
                // splicing the captured override.
                val toRemove = mutableSetOf<String>()
                val slotsToRemove = mutableSetOf<String>()
                for (childId in phFillSlot.children) {
                    collectDescendants(childId, nodes, slots, toRemove, slotsToRemove)
                }
                for (id in toRemove) nodes.remove(id)
                for (id in slotsToRemove) slots.remove(id)

                // Re-key the captured fill into fresh IDs and merge into the doc.
                val reKeyedFill = reKeyFragment(capture)
                for (node in reKeyedFill.nodes) nodes[node.id] = node
                for (slot2 in reKeyedFill.slots) slots[slot2.id] = slot2
                slots[phFillSlotId] = phFillSlot.copy(children = reKeyedFill.rootChildIds)
            }

            // Record fills that did not match a placeholder in the new version.
            val dropped = capturedFills.filterKeys { it !in matchedNames }
            if (dropped.isNotEmpty()) {
                droppedFillsByInstance[stencilNode.id] = dropped.map { (name, capture) ->
                    DroppedFill(name = name, contentSummary = summarize(capture, nodes))
                }
            }
        }

        return UpgradeResult(
            document = document.copy(nodes = nodes, slots = slots),
            droppedFills = droppedFillsByInstance,
            droppedBindings = droppedBindingsByInstance,
            unboundRequired = unboundRequiredByInstance,
        )
    }

    /**
     * Walks the children of [rootChildren] (which would be the children of a
     * stencil node's slot) looking for `placeholder` nodes. For each, captures
     * everything in its `fill` slot (deep) by reference into the source maps.
     *
     * Slots are looked up by name — placeholders own a `default` slot AND a
     * `fill` slot, and only the `fill` slot's content is the user's override
     * (the default lives in the stencil's content and gets re-keyed on upgrade
     * along with the rest of the stencil). Capturing the default would
     * incorrectly preserve stale stencil content across an upgrade.
     */
    private fun captureFillsByName(
        rootChildren: List<String>,
        nodes: Map<String, Node>,
        slots: Map<String, Slot>,
    ): Map<String, CapturedFill> {
        val result = mutableMapOf<String, CapturedFill>()
        val queue: ArrayDeque<String> = ArrayDeque(rootChildren)
        while (queue.isNotEmpty()) {
            val nodeId = queue.removeFirst()
            val node = nodes[nodeId] ?: continue

            if (node.type == PlaceholderNodeKeys.NODE_TYPE) {
                val name = node.props?.get(PlaceholderNodeKeys.PROP_NAME) as? String
                val fillSlot = node.slots
                    .mapNotNull { slots[it] }
                    .firstOrNull { it.name == PlaceholderNodeKeys.SLOT_FILL }
                if (name != null && fillSlot != null && fillSlot.children.isNotEmpty()) {
                    // Snapshot the fill's subtree.
                    val collectedNodes = mutableMapOf<String, Node>()
                    val collectedSlots = mutableMapOf<String, Slot>()
                    for (childId in fillSlot.children) {
                        walkSubtree(childId, nodes, slots, collectedNodes, collectedSlots)
                    }
                    result[name] = CapturedFill(
                        rootChildIds = fillSlot.children.toList(),
                        nodes = collectedNodes,
                        slots = collectedSlots,
                    )
                }
                // Do not descend into a placeholder's fill — captured holistically.
                continue
            }

            for (slotId in node.slots) {
                val slot = slots[slotId] ?: continue
                queue.addAll(slot.children)
            }
        }
        return result
    }

    private fun walkSubtree(
        nodeId: String,
        nodes: Map<String, Node>,
        slots: Map<String, Slot>,
        outNodes: MutableMap<String, Node>,
        outSlots: MutableMap<String, Slot>,
    ) {
        val node = nodes[nodeId] ?: return
        if (outNodes.containsKey(nodeId)) return // defensive
        outNodes[nodeId] = node
        for (slotId in node.slots) {
            val slot = slots[slotId] ?: continue
            outSlots[slotId] = slot
            for (childId in slot.children) {
                walkSubtree(childId, nodes, slots, outNodes, outSlots)
            }
        }
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

        for (nodeId in content.nodes.keys) {
            nodeIdMap[nodeId] = generateId()
        }
        for (slotId in content.slots.keys) {
            slotIdMap[slotId] = generateId()
        }

        val rootNode = content.nodes[content.root]
        val rootSlotId = rootNode?.slots?.firstOrNull()

        val nodes = content.nodes.values
            .filter { it.id != content.root }
            .map { node ->
                node.copy(
                    id = nodeIdMap[node.id]!!,
                    slots = node.slots.map { slotIdMap[it]!! },
                )
            }

        val slots = content.slots.values
            .filter { it.id != rootSlotId }
            .map { slot ->
                slot.copy(
                    id = slotIdMap[slot.id]!!,
                    nodeId = nodeIdMap[slot.nodeId]!!,
                    children = slot.children.map { nodeIdMap[it]!! },
                )
            }

        val rootSlot = rootSlotId?.let { content.slots[it] }
        val childNodeIds = rootSlot
            ?.children?.map { nodeIdMap[it]!! }
            ?: listOf(nodeIdMap[content.root]!!)

        return ReKeyResult(childNodeIds, nodes, slots)
    }

    /**
     * Re-keys a captured fill subtree with fresh IDs. Returns the new top-level
     * child IDs (replacements for the captured `rootChildIds`) and the full set
     * of re-keyed nodes/slots that need to be merged into the host document.
     */
    private fun reKeyFragment(capture: CapturedFill): ReKeyedFragment {
        val nodeIdMap = capture.nodes.keys.associateWith { generateId() }
        val slotIdMap = capture.slots.keys.associateWith { generateId() }

        val nodes = capture.nodes.values.map { node ->
            node.copy(
                id = nodeIdMap[node.id]!!,
                slots = node.slots.map { slotIdMap.getValue(it) },
            )
        }
        val slots = capture.slots.values.map { slot ->
            slot.copy(
                id = slotIdMap[slot.id]!!,
                nodeId = nodeIdMap.getValue(slot.nodeId),
                children = slot.children.map { nodeIdMap.getValue(it) },
            )
        }
        val rootChildIds = capture.rootChildIds.map { nodeIdMap.getValue(it) }
        return ReKeyedFragment(rootChildIds = rootChildIds, nodes = nodes, slots = slots)
    }

    private fun generateId(): String = UUID.randomUUID().toString().replace("-", "").take(21)

    /**
     * Builds a short human-friendly summary of a captured fill — used by the UI
     * to show the operator what content was about to be discarded. Picks the
     * first non-empty plain-text content; falls back to a node-type list.
     */
    private fun summarize(capture: CapturedFill, currentNodes: Map<String, Node>): String {
        val firstNode = capture.rootChildIds
            .asSequence()
            .map { capture.nodes[it] ?: currentNodes[it] }
            .firstOrNull { it != null }
        if (firstNode != null) {
            val content = firstNode.props?.get("content")
            if (content is String && content.isNotBlank()) {
                return content.take(80)
            }
        }
        val types = capture.rootChildIds
            .map { capture.nodes[it]?.type ?: currentNodes[it]?.type ?: "?" }
            .distinct()
        return "[" + types.joinToString(", ") + "]"
    }

    private data class ReKeyResult(
        val childNodeIds: List<String>,
        val nodes: List<Node>,
        val slots: List<Slot>,
    )

    private data class ReKeyedFragment(
        val rootChildIds: List<String>,
        val nodes: List<Node>,
        val slots: List<Slot>,
    )

    private data class CapturedFill(
        val rootChildIds: List<String>,
        val nodes: Map<String, Node>,
        val slots: Map<String, Slot>,
    )
}

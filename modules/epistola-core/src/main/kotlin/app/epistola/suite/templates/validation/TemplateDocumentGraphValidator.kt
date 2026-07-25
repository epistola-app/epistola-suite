// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.validation

import app.epistola.generation.pdf.SupportedNodeTypes
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import app.epistola.template.model.TemplateDocument

/**
 * Validates the node/slot graph invariants shared by templates and stencil
 * definitions before deeper validators, path extraction, or rendering traverse
 * the document. Errors use paths relative to the TemplateDocument; the validation
 * facade adds the request field that contains it.
 */
internal class TemplateDocumentGraphValidator {
    fun validate(doc: TemplateDocument) {
        validateSize(doc)
        validateRoot(doc)
        validateNodeMap(doc)
        validateSlotMap(doc)
        validateReachabilityAndCycles(doc)
    }

    private fun validateSize(doc: TemplateDocument) {
        if (doc.nodes.size > MAX_NODES) {
            graphError(
                "nodes",
                "template document has ${doc.nodes.size} nodes; maximum is $MAX_NODES",
            )
        }
        if (doc.slots.size > MAX_SLOTS) {
            graphError(
                "slots",
                "template document has ${doc.slots.size} slots; maximum is $MAX_SLOTS",
            )
        }
        for ((nodeId, node) in doc.nodes) {
            if (node.slots.size > MAX_SLOTS) {
                graphError(
                    "nodes.$nodeId.slots",
                    "node '$nodeId' has ${node.slots.size} slot references; maximum is $MAX_SLOTS",
                )
            }
        }
        for ((slotId, slot) in doc.slots) {
            if (slot.children.size > MAX_NODES) {
                graphError(
                    "slots.$slotId.children",
                    "slot '$slotId' has ${slot.children.size} child references; maximum is $MAX_NODES",
                )
            }
        }
    }

    private fun validateRoot(doc: TemplateDocument) {
        if (doc.root.isBlank()) {
            graphError("root", "template document root is required")
        }
        val rootNode = doc.nodes[doc.root]
            ?: graphError("root", "root node '${doc.root}' is missing from nodes")
        if (rootNode.type != ROOT_NODE_TYPE) {
            graphError("root", "root node '${doc.root}' must have type '$ROOT_NODE_TYPE'")
        }
    }

    private fun validateNodeMap(doc: TemplateDocument) {
        var rootCount = 0
        for ((key, node) in doc.nodes) {
            if (key != node.id) {
                graphError("nodes.$key.id", "node map key '$key' does not match node id '${node.id}'")
            }
            if (node.id.isBlank()) {
                graphError("nodes.$key.id", "node id must not be blank")
            }
            if (node.type.isBlank()) {
                graphError("nodes.$key.type", "node '${node.id}' type must not be blank")
            }
            if (node.type !in SupportedNodeTypes.all) {
                throw ValidationException(
                    "nodes.$key.type",
                    "node '${node.id}' uses unsupported type '${node.type}'",
                    ValidationCode.TEMPLATE_NODE_TYPE_UNSUPPORTED,
                )
            }
            val duplicateSlot = node.slots.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
            if (duplicateSlot != null) {
                graphError(
                    "nodes.$key.slots",
                    "node '${node.id}' references slot '$duplicateSlot' more than once",
                )
            }
            if (node.type == ROOT_NODE_TYPE) rootCount += 1
        }
        if (rootCount != 1) {
            graphError("nodes", "template document must contain exactly one root node, found $rootCount")
        }
    }

    private fun validateSlotMap(doc: TemplateDocument) {
        for ((key, slot) in doc.slots) {
            if (key != slot.id) {
                graphError("slots.$key.id", "slot map key '$key' does not match slot id '${slot.id}'")
            }
            if (slot.id.isBlank()) {
                graphError("slots.$key.id", "slot id must not be blank")
            }
            if (slot.nodeId !in doc.nodes) {
                graphError("slots.$key.nodeId", "slot '${slot.id}' owner node '${slot.nodeId}' is missing")
            }
            val owner = doc.nodes[slot.nodeId]
            if (owner != null && slot.id !in owner.slots) {
                graphError("slots.$key.nodeId", "slot '${slot.id}' is not listed by owner node '${slot.nodeId}'")
            }
            val duplicateChild = slot.children.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
            if (duplicateChild != null) {
                graphError(
                    "slots.$key.children",
                    "slot '${slot.id}' references child node '$duplicateChild' more than once",
                )
            }
            for (childId in slot.children) {
                if (childId !in doc.nodes) {
                    graphError("slots.$key.children", "slot '${slot.id}' references missing child node '$childId'")
                }
            }
        }

        for (node in doc.nodes.values) {
            for (slotId in node.slots) {
                val slot = doc.slots[slotId]
                    ?: graphError("nodes.${node.id}.slots", "node '${node.id}' references missing slot '$slotId'")
                if (slot.nodeId != node.id) {
                    graphError(
                        "nodes.${node.id}.slots",
                        "node '${node.id}' references slot '$slotId' owned by '${slot.nodeId}'",
                    )
                }
            }
        }
    }

    private fun validateReachabilityAndCycles(doc: TemplateDocument) {
        val parents = mutableMapOf<String, String>()
        for (slot in doc.slots.values) {
            for (childId in slot.children) {
                val previousParentSlot = parents.put(childId, slot.id)
                if (previousParentSlot != null) {
                    graphError(
                        "slots.${slot.id}.children",
                        "node '$childId' has multiple parents: '$previousParentSlot' and '${slot.id}'",
                    )
                }
            }
        }
        if (parents.containsKey(doc.root)) {
            graphError("root", "root node '${doc.root}' must not be a child of any slot")
        }

        val reachableNodes = mutableSetOf<String>()
        val reachableSlots = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()

        fun visit(nodeId: String, depth: Int) {
            if (depth > MAX_DEPTH) {
                graphError("nodes.$nodeId", "template document exceeds maximum depth $MAX_DEPTH")
            }
            if (!visiting.add(nodeId)) {
                graphError("nodes.$nodeId", "template document contains a cycle through node '$nodeId'")
            }
            reachableNodes.add(nodeId)
            val node = doc.nodes[nodeId]
                ?: graphError("nodes.$nodeId", "node '$nodeId' is missing")
            for (slotId in node.slots) {
                reachableSlots.add(slotId)
                val slot = doc.slots[slotId]
                    ?: graphError("slots.$slotId", "slot '$slotId' is missing")
                for (childId in slot.children) {
                    visit(childId, depth + 1)
                }
            }
            visiting.remove(nodeId)
        }

        visit(doc.root, 0)

        val unreachableNodes = doc.nodes.keys - reachableNodes
        if (unreachableNodes.isNotEmpty()) {
            graphError("nodes", "unreachable node(s): ${unreachableNodes.sorted().joinToString(", ")}")
        }
        val unreachableSlots = doc.slots.keys - reachableSlots
        if (unreachableSlots.isNotEmpty()) {
            graphError("slots", "unreachable slot(s): ${unreachableSlots.sorted().joinToString(", ")}")
        }
    }

    private fun graphError(field: String, message: String): Nothing = throw ValidationException(field, message, ValidationCode.TEMPLATE_GRAPH_INVALID)

    companion object {
        const val MAX_NODES = 500
        const val MAX_SLOTS = 750
        const val MAX_DEPTH = 100
        private const val ROOT_NODE_TYPE = "root"
    }
}

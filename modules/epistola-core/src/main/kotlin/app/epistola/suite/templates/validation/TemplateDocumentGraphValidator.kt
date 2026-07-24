// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.validation

import app.epistola.generation.pdf.SupportedNodeTypes
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import app.epistola.template.model.TemplateDocument
import org.springframework.stereotype.Component

/**
 * Validates the node/slot graph invariants shared by templates and stencil
 * definitions before deeper validators, path extraction, or rendering traverse
 * the document.
 */
@Component
class TemplateDocumentGraphValidator {
    /**
     * Templates and stencils are both stored as complete TemplateDocuments.
     * Stencil insertion/replacement treats the stored stencil root as a container
     * whose children become the embedded content.
     */
    fun validateStencilDocument(doc: TemplateDocument) = validate(doc)

    fun validateTemplateDocument(doc: TemplateDocument) = validate(doc)

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
                "content.nodes",
                "template document has ${doc.nodes.size} nodes; maximum is $MAX_NODES",
            )
        }
        if (doc.slots.size > MAX_SLOTS) {
            graphError(
                "content.slots",
                "template document has ${doc.slots.size} slots; maximum is $MAX_SLOTS",
            )
        }
    }

    private fun validateRoot(doc: TemplateDocument) {
        if (doc.root.isBlank()) {
            graphError("content.root", "template document root is required")
        }
        val rootNode = doc.nodes[doc.root]
            ?: graphError("content.root", "root node '${doc.root}' is missing from nodes")
        if (rootNode.type != ROOT_NODE_TYPE) {
            graphError("content.root", "root node '${doc.root}' must have type '$ROOT_NODE_TYPE'")
        }
    }

    private fun validateNodeMap(doc: TemplateDocument) {
        var rootCount = 0
        for ((key, node) in doc.nodes) {
            if (key != node.id) {
                graphError("content.nodes.$key.id", "node map key '$key' does not match node id '${node.id}'")
            }
            if (node.id.isBlank()) {
                graphError("content.nodes.$key.id", "node id must not be blank")
            }
            if (node.type.isBlank()) {
                graphError("content.nodes.$key.type", "node '${node.id}' type must not be blank")
            }
            if (node.type !in SupportedNodeTypes.all) {
                throw ValidationException(
                    "content.nodes.$key.type",
                    "node '${node.id}' uses unsupported type '${node.type}'",
                    ValidationCode.TEMPLATE_NODE_TYPE_UNSUPPORTED,
                )
            }
            if (node.type == ROOT_NODE_TYPE) rootCount += 1
        }
        if (rootCount != 1) {
            graphError("content.nodes", "template document must contain exactly one root node, found $rootCount")
        }
    }

    private fun validateSlotMap(doc: TemplateDocument) {
        for ((key, slot) in doc.slots) {
            if (key != slot.id) {
                graphError("content.slots.$key.id", "slot map key '$key' does not match slot id '${slot.id}'")
            }
            if (slot.id.isBlank()) {
                graphError("content.slots.$key.id", "slot id must not be blank")
            }
            if (slot.nodeId !in doc.nodes) {
                graphError("content.slots.$key.nodeId", "slot '${slot.id}' owner node '${slot.nodeId}' is missing")
            }
            val owner = doc.nodes[slot.nodeId]
            if (owner != null && slot.id !in owner.slots) {
                graphError("content.slots.$key.nodeId", "slot '${slot.id}' is not listed by owner node '${slot.nodeId}'")
            }
            for (childId in slot.children) {
                if (childId !in doc.nodes) {
                    graphError("content.slots.$key.children", "slot '${slot.id}' references missing child node '$childId'")
                }
            }
        }

        for (node in doc.nodes.values) {
            for (slotId in node.slots) {
                val slot = doc.slots[slotId]
                    ?: graphError("content.nodes.${node.id}.slots", "node '${node.id}' references missing slot '$slotId'")
                if (slot.nodeId != node.id) {
                    graphError(
                        "content.nodes.${node.id}.slots",
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
                        "content.slots.${slot.id}.children",
                        "node '$childId' has multiple parents: '$previousParentSlot' and '${slot.id}'",
                    )
                }
            }
        }
        if (parents.containsKey(doc.root)) {
            graphError("content.root", "root node '${doc.root}' must not be a child of any slot")
        }

        val reachableNodes = mutableSetOf<String>()
        val reachableSlots = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()

        fun visit(nodeId: String, depth: Int) {
            if (depth > MAX_DEPTH) {
                graphError("content.nodes.$nodeId", "template document exceeds maximum depth $MAX_DEPTH")
            }
            if (!visiting.add(nodeId)) {
                graphError("content.nodes.$nodeId", "template document contains a cycle through node '$nodeId'")
            }
            reachableNodes.add(nodeId)
            val node = doc.nodes[nodeId]
                ?: graphError("content.nodes.$nodeId", "node '$nodeId' is missing")
            for (slotId in node.slots) {
                reachableSlots.add(slotId)
                val slot = doc.slots[slotId]
                    ?: graphError("content.slots.$slotId", "slot '$slotId' is missing")
                for (childId in slot.children) {
                    visit(childId, depth + 1)
                }
            }
            visiting.remove(nodeId)
        }

        visit(doc.root, 0)

        val unreachableNodes = doc.nodes.keys - reachableNodes
        if (unreachableNodes.isNotEmpty()) {
            graphError("content.nodes", "unreachable node(s): ${unreachableNodes.sorted().joinToString(", ")}")
        }
        val unreachableSlots = doc.slots.keys - reachableSlots
        if (unreachableSlots.isNotEmpty()) {
            graphError("content.slots", "unreachable slot(s): ${unreachableSlots.sorted().joinToString(", ")}")
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

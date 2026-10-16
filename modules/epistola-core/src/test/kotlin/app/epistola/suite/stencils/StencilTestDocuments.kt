// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils

import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.NodeParameterKeys
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.template.model.ThemeRef

/**
 * Builds template and stencil documents with stencil instances in their stored form (contract
 * ADR 0006): an instance holds its fills as placeholder carriers, nothing of the stencil itself.
 */
class StencilTestDocument {
    private val nodes = linkedMapOf<String, Node>()
    private val slots = linkedMapOf<String, Slot>()

    fun node(id: String, type: String, props: Map<String, Any?>? = null, vararg slotChildren: Pair<String, List<String>>): String {
        val slotIds = slotChildren.map { (name, children) ->
            "$id/$name".also { slots[it] = Slot(id = it, nodeId = id, name = name, children = children) }
        }
        nodes[id] = Node(id = id, type = type, slots = slotIds, props = props)
        return id
    }

    fun text(id: String, value: String) = node(id, "text", mapOf("content" to richText(value)))

    fun placeholder(id: String, name: String, default: List<String> = emptyList(), fill: List<String> = emptyList()) = node(
        id,
        PlaceholderNodeKeys.NODE_TYPE,
        mapOf(PlaceholderNodeKeys.PROP_NAME to name),
        PlaceholderNodeKeys.SLOT_DEFAULT to default,
        PlaceholderNodeKeys.SLOT_FILL to fill,
    )

    /** An instance of [key] whose children are its fills (placeholders carrying the using side's content). */
    fun stencil(
        id: String,
        key: String,
        fills: List<String> = emptyList(),
        catalogKey: String? = null,
        bindings: Map<String, Any?>? = null,
    ) = node(
        id,
        StencilNodeKeys.NODE_TYPE,
        buildMap {
            put(StencilNodeKeys.PROP_STENCIL_KEY, key)
            catalogKey?.let { put(StencilNodeKeys.PROP_CATALOG_KEY, it) }
            bindings?.let { put(NodeParameterKeys.PROP_PARAMETER_BINDINGS, it) }
        },
        StencilNodeKeys.SLOT_CHILDREN to fills,
    )

    fun build(vararg rootChildren: String): TemplateDocument {
        node("root", "root", null, "children" to rootChildren.toList())
        return TemplateDocument(modelVersion = 1, root = "root", nodes = nodes, slots = slots, themeRef = ThemeRef.Inherit)
    }
}

fun stencilDocument(content: StencilTestDocument.() -> TemplateDocument): TemplateDocument = StencilTestDocument().content()

/** A one-paragraph rich-text value, as a text node's `content` holds it. */
fun richText(text: String): Map<String, Any?> = mapOf(
    "type" to "doc",
    "content" to listOf(mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to text)))),
)

/** Every text a document's text nodes hold, in node order. */
fun TemplateDocument.texts(): List<String> = nodes.values.filter { it.type == "text" }.mapNotNull { node ->
    @Suppress("UNCHECKED_CAST")
    val doc = node.props?.get("content") as? Map<String, Any?> ?: return@mapNotNull null
    fun collect(value: Any?): List<String> = when (value) {
        is Map<*, *> -> listOfNotNull(value["text"] as? String) + collect(value["content"])
        is List<*> -> value.flatMap(::collect)
        else -> emptyList()
    }
    collect(doc).joinToString("")
}

package app.epistola.suite.catalog.migrations.steps

import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * Appends a single text block carrying [text] to every `TemplateDocument` in a
 * template resource-detail tree — the top-level `templateModel` and each
 * variant's `templateModel`. The example migrations use this to show a real
 * content transform on import.
 *
 * The block is a `text` node `{ type, props: { content: <ProseMirror doc> } }`
 * appended to the document root's children slot. It is **idempotent** (keyed on
 * [nodeId], so re-running adds nothing) and a **no-op for non-template resources**.
 */
internal fun injectTemplateNotice(detail: ObjectNode, nodeId: String, text: String) {
    val resource = detail.get("resource") as? ObjectNode ?: return
    if (resource.get("type")?.takeIf { it.isString }?.asString() != "template") return

    (resource.get("templateModel") as? ObjectNode)?.let { appendTextBlock(it, nodeId, text) }
    (resource.get("variants") as? ArrayNode)?.forEach { v ->
        ((v as? ObjectNode)?.get("templateModel") as? ObjectNode)?.let { appendTextBlock(it, nodeId, text) }
    }
}

private fun appendTextBlock(model: ObjectNode, nodeId: String, text: String) {
    val nodes = model.get("nodes") as? ObjectNode ?: return
    if (nodes.has(nodeId)) return // idempotent — already injected
    val rootId = model.get("root")?.takeIf { it.isString }?.asString() ?: return
    val rootSlotId = ((nodes.get(rootId) as? ObjectNode)?.get("slots") as? ArrayNode)
        ?.firstOrNull()?.takeIf { it.isString }?.asString() ?: return
    val children = ((model.get("slots") as? ObjectNode)?.get(rootSlotId) as? ObjectNode)
        ?.get("children") as? ArrayNode ?: return

    // text node: { id, type: "text", slots: [], props: { content: <ProseMirror doc> } }
    val textNode = nodes.putObject(nodeId)
    textNode.put("id", nodeId)
    textNode.put("type", "text")
    textNode.putArray("slots")
    val content = textNode.putObject("props").putObject("content")
    content.put("type", "doc")
    val paragraph = content.putArray("content").addObject()
    paragraph.put("type", "paragraph")
    paragraph.putArray("content").addObject().also {
        it.put("type", "text")
        it.put("text", text)
    }

    children.add(nodeId)
}

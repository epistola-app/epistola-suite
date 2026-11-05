// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.testing

import app.epistola.template.model.TemplateDocument
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.KotlinModule

/** One example from the component registry, as a document that renders on its own. */
data class RegistryExample(val blockType: String, val name: String, val document: TemplateDocument) {
    /** `<block type>/<example name>`, e.g. `qrcode/static-string`. */
    val id: String get() = "$blockType/$name"
}

/**
 * The examples of the contract's component registry, the ones the editor requires per block and the
 * MCP server serves, as renderable documents, with data that satisfies their expressions.
 *
 * Shared by the tests that render every example (`PdfConformanceTest` in this module, the MCP
 * `ExampleRenderingIntegrationTest`), so a new example that needs new data or wrapping is changed
 * in one place.
 */
object RegistryExamples {

    private const val REGISTRY = "/META-INF/epistola-catalog/component-registry.json"
    private const val ROOT = "n-example-root"
    private const val ROOT_SLOT = "s-example-root"
    private const val TITLE = "n-example-title"
    private const val STENCIL = "n-example-stencil"
    private const val STENCIL_SLOT = "s-example-stencil"

    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    /** Satisfies every expression the registry examples reference; a new example may need an entry. */
    val sampleData: Map<String, Any?> = mapOf(
        "recipient" to mapOf("name" to "Jane Doe", "address" to "123 Main Street", "city" to "Amsterdam"),
        "subtotal" to 99.95,
        "notes" to "Please pay within 30 days.",
        "paid" to false,
        "paymentLink" to "https://pay.example.com/abc123",
        "attendees" to listOf(
            mapOf("name" to "Alice Example", "email" to "alice@example.com"),
            mapOf("name" to "Bob Example", "email" to "bob@example.com"),
        ),
        "features" to listOf("Fast", "Secure", "Open source"),
        "steps" to listOf(mapOf("title" to "Sign up"), mapOf("title" to "Verify email")),
        "tags" to listOf("New", "Popular"),
        "items" to listOf(
            mapOf("description" to "Widget A", "quantity" to 2, "amount" to 49.95),
            mapOf("description" to "Widget B", "quantity" to 1, "amount" to 19.95),
        ),
        "tenant" to mapOf("name" to "Acme Corp"),
    )

    /** Every example in registry order. */
    fun all(): List<RegistryExample> = components().flatMap { component ->
        val type = component.get("type").asString()
        ((component.get("examples") as? ArrayNode)?.values() ?: emptyList()).map { example ->
            RegistryExample(type, example.get("name").asString(), wrap(type, example.get("fragment") as ObjectNode))
        }
    }

    /** The example with this [RegistryExample.id]. */
    fun named(id: String): TemplateDocument = all().first { it.id == id }.document

    /** Every block type the registry declares, with or without an example. */
    fun blockTypes(): Set<String> = components().map { it.get("type").asString() }.toSet()

    private fun components(): List<ObjectNode> {
        val registry = requireNotNull(RegistryExamples::class.java.getResourceAsStream(REGISTRY)) { "$REGISTRY is not on the classpath" }
            .use { json.readTree(it) as ObjectNode }
        return registry.get("components").values().map { it as ObjectNode }
    }

    /**
     * A fragment becomes a document: a root example already is one; anything else gets a synthetic
     * root with a title above it, nodes the fragment root does not reach attached beside it, and a
     * placeholder gets the stencil ancestor it needs.
     */
    private fun wrap(type: String, fragment: ObjectNode): TemplateDocument {
        val fragmentRoot = fragment.get("rootNodeId").asString()
        val nodes = fragment.get("nodes") as ObjectNode
        val slots = fragment.get("slots") as ObjectNode
        if (type == "root") return document(fragmentRoot, nodes, slots)

        val reachable = reachable(fragmentRoot, nodes, slots)
        val tops = listOf(fragmentRoot) + nodes.propertyNames().filter { it !in reachable }
        val allNodes = json.createObjectNode().setAll(nodes)
        val allSlots = json.createObjectNode().setAll(slots)

        val rootChildren = if (type == "placeholder") {
            allNodes.set(STENCIL, node(STENCIL, "stencil", STENCIL_SLOT).set("props", json.createObjectNode().put("stencilKey", "example")))
            allSlots.set(STENCIL_SLOT, slot(STENCIL_SLOT, STENCIL, tops))
            listOf(STENCIL)
        } else {
            tops
        }
        allNodes.set(TITLE, title())
        allNodes.set(ROOT, node(ROOT, "root", ROOT_SLOT))
        allSlots.set(ROOT_SLOT, slot(ROOT_SLOT, ROOT, listOf(TITLE) + rootChildren))
        return document(ROOT, allNodes, allSlots)
    }

    /**
     * A fragment sits inside a page, under that page's title: an example's section heading is an H2
     * because something above it is the H1. Rendered alone it would open the document at an H2, which
     * PDF/UA-1 does not allow (ISO 14289-1, 7.4.2).
     */
    private fun title(): ObjectNode = json.createObjectNode().put("id", TITLE).put("type", "text").set(
        "props",
        json.createObjectNode().set(
            "content",
            json.readTree("""{"type":"doc","content":[{"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"Example letter"}]}]}"""),
        ),
    )

    private fun node(id: String, type: String, slotId: String): ObjectNode = json.createObjectNode().put("id", id).put("type", type)
        .set("slots", json.createArrayNode().add(slotId))

    private fun slot(id: String, nodeId: String, children: List<String>): ObjectNode = json.createObjectNode()
        .put("id", id).put("nodeId", nodeId).put("name", "children")
        .set("children", json.createArrayNode().also { array -> children.forEach(array::add) })

    private fun document(root: String, nodes: ObjectNode, slots: ObjectNode): TemplateDocument {
        val doc = json.createObjectNode().put("modelVersion", 1).put("root", root)
        doc.set("themeRef", json.createObjectNode().put("type", "inherit"))
        doc.set("nodes", nodes)
        doc.set("slots", slots)
        return json.treeToValue(doc, TemplateDocument::class.java)
    }

    private fun reachable(root: String, nodes: ObjectNode, slots: ObjectNode): Set<String> {
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!seen.add(id)) continue
            val slotIds = (nodes.get(id) as? ObjectNode)?.get("slots") as? ArrayNode ?: continue
            for (slotId in slotIds.values()) {
                val children = (slots.get(slotId.asString()) as? ObjectNode)?.get("children") as? ArrayNode ?: continue
                children.values().forEach { queue.add(it.asString()) }
            }
        }
        return seen
    }
}

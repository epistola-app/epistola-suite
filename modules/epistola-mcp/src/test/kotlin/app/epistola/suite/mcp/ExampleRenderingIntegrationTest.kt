// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.documents.queries.PreviewVariant
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.template.model.TemplateDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * Smoke test that every hand-curated example actually renders to a non-empty
 * PDF when wrapped in a TemplateDocument and fed through `PreviewVariant`.
 *
 * Goes one level beyond the structural validation in
 * `ComponentTypesIntegrationTest` and `registry-examples.test.ts`: it executes
 * the real generation pipeline (theme cascade, expression evaluation,
 * iText rendering), so authoring mistakes that pass JSON-schema validation —
 * a malformed JSONata expression, an inspector prop with the wrong type,
 * an asset reference to nothing — surface here as a render failure.
 *
 * One template is created per test run; every example reuses it via the
 * `templateModel` override on `PreviewVariant`. Each render is a few ms;
 * the entire test typically runs in < 1s on top of the shared Spring
 * context boot.
 */
@Timeout(60)
class ExampleRenderingIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Value("classpath:META-INF/epistola-catalog/component-registry.json")
    private lateinit var registryResource: Resource

    /**
     * Sample data that satisfies every expression referenced by a curated
     * example. Each new example whose expression touches a new field needs
     * a corresponding entry here. Kept JSON-shaped so the renderer's
     * JSONata evaluator can resolve paths.
     */
    private fun sampleData(): ObjectNode = objectMapper.readTree(
        """
        {
          "recipient": { "name": "Jane Doe", "address": "123 Main Street", "city": "Amsterdam" },
          "subtotal": 99.95,
          "notes": "Please pay within 30 days.",
          "paid": false,
          "paymentLink": "https://pay.example.com/abc123",
          "attendees": [
            { "name": "Alice Example", "email": "alice@example.com" },
            { "name": "Bob Example", "email": "bob@example.com" }
          ],
          "features": ["Fast", "Secure", "Open source"],
          "steps": [
            { "title": "Sign up" },
            { "title": "Verify email" }
          ],
          "items": [
            { "description": "Widget A", "quantity": 2, "amount": 49.95 },
            { "description": "Widget B", "quantity": 1, "amount": 19.95 }
          ],
          "tenant": { "name": "Acme Corp" }
        }
        """.trimIndent(),
    ) as ObjectNode

    @Test
    fun `every component example renders to a non-empty PDF`() {
        val tenant = createTenant("Examples Render Tenant")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val variantId = VariantKey.INITIAL

        withMediator {
            CreateDocumentTemplate(id = templateId, name = "Examples Test Template").execute()
        }

        val data = sampleData()
        val failures = mutableListOf<String>()
        var rendered = 0

        eachExample { componentType, exampleName, fragment ->
            val templateModel = wrapAsTemplateDocument(componentType, fragment)
            try {
                val pdfBytes = withMediator {
                    PreviewVariant(
                        tenantId = tenant.id,
                        catalogKey = CatalogKey.DEFAULT,
                        templateId = templateId.key,
                        variantId = variantId,
                        data = data,
                        templateModel = templateModel,
                    ).query()
                }
                if (pdfBytes.isEmpty()) {
                    failures += "$componentType/$exampleName: empty PDF"
                } else {
                    rendered++
                }
            } catch (e: Exception) {
                failures += "$componentType/$exampleName: ${e.javaClass.simpleName}: ${e.message}"
            }
        }

        assertThat(failures)
            .withFailMessage("rendering failures (rendered ok=$rendered):\n${failures.joinToString("\n")}")
            .isEmpty()
        assertThat(rendered).isGreaterThan(0)
    }

    /**
     * Walks the component registry JSON and invokes `block` for every
     * (componentType, exampleName, fragment) tuple. Reading the raw JSON
     * directly (vs. the typed DTOs) keeps the test independent of the
     * Kotlin DTO layout — if we add fields to ComponentExampleInfo later,
     * this loop keeps working.
     */
    private fun eachExample(block: (componentType: String, exampleName: String, fragment: ObjectNode) -> Unit) {
        val root = registryResource.inputStream.use { objectMapper.readTree(it) }
        val components = root.get("components") as ArrayNode
        for (componentNode in components.values()) {
            val componentType = componentNode.get("type").asString()
            val examples = componentNode.get("examples") as? ArrayNode ?: continue
            for (exampleNode in examples.values()) {
                val exampleName = exampleNode.get("name").asString()
                val fragment = exampleNode.get("fragment") as ObjectNode
                block(componentType, exampleName, fragment)
            }
        }
    }

    /**
     * Builds a valid TemplateDocument for each component example. Root examples
     * already provide the document root. Other fragments get a synthetic root,
     * with any orphan top-level nodes attached as siblings. Placeholder examples
     * additionally get a synthetic stencil ancestor because placeholders are
     * only valid inside stencils.
     */
    private fun wrapAsTemplateDocument(componentType: String, fragment: ObjectNode): TemplateDocument {
        val rootNodeId = fragment.get("rootNodeId").asString()
        val fragmentNodes = fragment.get("nodes") as ObjectNode
        val fragmentSlots = fragment.get("slots") as ObjectNode

        if (componentType == "root") {
            return createTemplateDocument(rootNodeId, fragmentNodes, fragmentSlots)
        }

        val syntheticRootId = "n-render-test-root"
        val syntheticSlotId = "s-render-test-children"

        val reachable = reachableNodeIds(rootNodeId, fragmentNodes, fragmentSlots)
        val orphans = fragmentNodes.propertyNames().filter { it !in reachable }
        val fragmentRoots = listOf(rootNodeId) + orphans
        val stencilNodeId = "n-render-test-stencil"
        val stencilSlotId = "s-render-test-stencil-children"
        val rootChildren = if (componentType == "placeholder") listOf(stencilNodeId) else fragmentRoots

        val syntheticRoot = objectMapper.createObjectNode()
            .put("id", syntheticRootId)
            .put("type", "root")
        syntheticRoot.set(
            "slots",
            objectMapper.createArrayNode().also { it.add(syntheticSlotId) },
        )

        val syntheticSlot = objectMapper.createObjectNode()
            .put("id", syntheticSlotId)
            .put("nodeId", syntheticRootId)
            .put("name", "children")
        syntheticSlot.set(
            "children",
            objectMapper.createArrayNode().also { array -> rootChildren.forEach { array.add(it) } },
        )

        val nodes = objectMapper.createObjectNode()
        nodes.set(syntheticRootId, syntheticRoot)
        if (componentType == "placeholder") {
            nodes.set(
                stencilNodeId,
                objectMapper.createObjectNode()
                    .put("id", stencilNodeId)
                    .put("type", "stencil")
                    .set("slots", objectMapper.createArrayNode().add(stencilSlotId))
                    .set(
                        "props",
                        objectMapper.createObjectNode()
                            .put("stencilId", "render-test")
                            .put("version", 1)
                            .put("isDraft", false),
                    ),
            )
        }
        for (key in fragmentNodes.propertyNames()) {
            nodes.set(key, fragmentNodes.get(key))
        }

        val slots = objectMapper.createObjectNode()
        slots.set(syntheticSlotId, syntheticSlot)
        if (componentType == "placeholder") {
            slots.set(
                stencilSlotId,
                objectMapper.createObjectNode()
                    .put("id", stencilSlotId)
                    .put("nodeId", stencilNodeId)
                    .put("name", "children")
                    .set(
                        "children",
                        objectMapper.createArrayNode().also { array -> fragmentRoots.forEach { array.add(it) } },
                    ),
            )
        }
        for (key in fragmentSlots.propertyNames()) {
            slots.set(key, fragmentSlots.get(key))
        }

        return createTemplateDocument(syntheticRootId, nodes, slots)
    }

    private fun createTemplateDocument(rootNodeId: String, nodes: ObjectNode, slots: ObjectNode): TemplateDocument {
        val themeRef = objectMapper.createObjectNode().put("type", "inherit")

        val docNode = objectMapper.createObjectNode()
            .put("modelVersion", 1)
            .put("root", rootNodeId)
        docNode.set("themeRef", themeRef)
        docNode.set("nodes", nodes)
        docNode.set("slots", slots)

        return objectMapper.treeToValue(docNode, TemplateDocument::class.java)
    }

    private fun reachableNodeIds(rootId: String, nodes: ObjectNode, slots: ObjectNode): Set<String> {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>().apply { add(rootId) }
        while (queue.isNotEmpty()) {
            val nodeId = queue.removeFirst()
            if (!visited.add(nodeId)) continue
            val node = nodes.get(nodeId) as? ObjectNode ?: continue
            val nodeSlots = node.get("slots") as? ArrayNode ?: continue
            for (slotIdNode in nodeSlots.values()) {
                val slot = slots.get(slotIdNode.asString()) as? ObjectNode ?: continue
                val children = slot.get("children") as? ArrayNode ?: continue
                for (childIdNode in children.values()) {
                    queue.add(childIdNode.asString())
                }
            }
        }
        return visited
    }
}

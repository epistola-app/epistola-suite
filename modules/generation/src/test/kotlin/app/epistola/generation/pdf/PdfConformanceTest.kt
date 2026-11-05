// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider
import org.verapdf.pdfa.Foundries
import org.verapdf.pdfa.flavours.PDFAFlavour
import org.verapdf.pdfa.results.TestAssertion
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.KotlinModule
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Validates rendered output with veraPDF, the reference validator, against the conformance every
 * document claims: PDF/UA-1 always (the renderer stamps the identifier on every document), and
 * PDF/A-2b when PDF/A is on (#453).
 *
 * The corpus is every example in the contract's component registry, the same examples the editor
 * requires per block and the MCP server serves, so a new block is covered the day it gets its
 * example. Synthetic documents add what the examples do not exercise. This checks the renderer,
 * not authors: it proves the node-to-tag mapping is valid. Problems an author causes, such as an
 * image without alt text, are the quality checks' business; [an image without alt text fails] keeps
 * this test able to tell the difference.
 */
class PdfConformanceTest {

    private val renderer = DirectPdfRenderer()
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    private val assets = AssetResolver { _, _ -> AssetResolution(pngBytes, "image/png") }

    /** Satisfies every expression the registry examples reference. */
    private val sampleData: Map<String, Any?> = mapOf(
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
        "items" to listOf(
            mapOf("description" to "Widget A", "quantity" to 2, "amount" to 49.95),
            mapOf("description" to "Widget B", "quantity" to 1, "amount" to 19.95),
        ),
        "tenant" to mapOf("name" to "Acme Corp"),
    )

    @TestFactory
    fun `every component example conforms`(): List<DynamicTest> = registryExamples().flatMap { (name, document) ->
        listOf(false, true).map { pdfa ->
            dynamicTest("$name, PDF/A ${if (pdfa) "on" else "off"}") {
                val known = KNOWN_VIOLATIONS[name].orEmpty()
                assertConforms(render(document, pdfa = pdfa), pdfa, expected = known, tolerated = if (pdfa) emptySet() else setOf(FONT_NOT_EMBEDDED))
            }
        }
    }

    @Test
    fun `a preview watermark conforms`() {
        val document = registryExamples().first { it.first == "text/minimal" }.second
        assertConforms(render(document, pdfa = true, watermarkText = "PREVIEW"), pdfa = true)
    }

    @Test
    fun `an address block on a letter that runs over several pages conforms`() {
        val example = registryExamples().first { it.first.startsWith("addressblock/") }.second
        val body = (1..12).map { "Paragraph $it " + "lorem ipsum ".repeat(60) }
        val bodyNode = Node(
            id = "n-long-body",
            type = "text",
            props = mapOf(
                "content" to mapOf(
                    "type" to "doc",
                    "content" to body.map { mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to it))) },
                ),
            ),
        )
        val rootSlot = example.slots.values.first { it.nodeId == example.root }
        val document = example.copy(
            nodes = example.nodes + (bodyNode.id to bodyNode),
            slots = example.slots + (rootSlot.id to rootSlot.copy(children = rootSlot.children + bodyNode.id)),
        )
        assertConforms(render(document, pdfa = true), pdfa = true)
    }

    @Test
    fun `without PDF-A the standard fonts are not embedded, which PDF-UA-1 forbids (known, #1308)`() {
        // Tolerated in every PDF/A-off render above. When #1308 is fixed this fails: drop the
        // tolerance there and this test.
        val document = registryExamples().first { it.first == "text/minimal" }.second
        val rules = violations(render(document, pdfa = false), PDFAFlavour.PDFUA_1).map { it.rule }
        assertEquals(listOf(FONT_NOT_EMBEDDED), rules)
    }

    @Test
    fun `an image without alt text fails PDF-UA, so the check can see an author's mistake`() {
        val image = Node(
            id = "n-image",
            type = "image",
            props = mapOf("imageKey" to "asset-1", "alt" to "", "width" to "80pt", "height" to "20pt"),
        )
        val document = TemplateDocument(
            root = "n-root",
            nodes = mapOf("n-root" to Node(id = "n-root", type = "root", slots = listOf("s-root")), image.id to image),
            slots = mapOf("s-root" to Slot(id = "s-root", nodeId = "n-root", name = "children", children = listOf(image.id))),
        )

        val violations = violations(render(document, pdfa = false), PDFAFlavour.PDFUA_1)

        assertEquals(listOf(FIGURE_WITHOUT_ALT), violations.map { it.rule }, "only the figure rule should fail, got:\n${violations.joinToString("\n")}")
    }

    @Test
    fun `the corpus covers every block type in the registry`() {
        val covered = registryExamples().map { it.first.substringBefore('/') }.toSet()
        val declared = registry().get("components").values().map { it.get("type").asString() }.toSet()
        assertEquals(declared, covered, "every block type needs at least one registry example")
    }

    // --- rendering ------------------------------------------------------------------------------

    private fun render(document: TemplateDocument, pdfa: Boolean, watermarkText: String? = null): ByteArray {
        val out = ByteArrayOutputStream()
        renderer.render(
            document = document,
            data = sampleData,
            outputStream = out,
            // As production passes it: the template name and the tenant (DocumentGenerationExecutor).
            metadata = PdfMetadata(title = "Conformance letter", author = "Acme Corp"),
            pdfaCompliant = pdfa,
            assetResolver = assets,
            renderMode = RenderMode.STRICT,
            watermarkText = watermarkText,
        )
        return out.toByteArray()
    }

    // --- validation -----------------------------------------------------------------------------

    /**
     * Fails on any violation not [expected] or [tolerated], and on an [expected] one that no longer
     * occurs: a fixed violation has to leave [KNOWN_VIOLATIONS], so the list only shrinks.
     */
    private fun assertConforms(pdf: ByteArray, pdfa: Boolean, expected: Set<String> = emptySet(), tolerated: Set<String> = emptySet()) {
        val flavours = listOfNotNull(PDFAFlavour.PDFUA_1, PDFAFlavour.PDFA_2_B.takeIf { pdfa })
        val violations = flavours.flatMap { violations(pdf, it) }
        val unexpected = violations.filter { it.rule !in expected && it.rule !in tolerated }
        assertEquals(emptyList(), unexpected, "veraPDF violations:\n${unexpected.joinToString("\n")}")
        val gone = expected - violations.map { it.rule }.toSet()
        assertEquals(emptySet(), gone, "listed in KNOWN_VIOLATIONS but no longer reported; remove them")
    }

    /** A failed veraPDF rule: its standard, clause and test number, and the first failure for the message. */
    private data class Violation(val rule: String, val count: Int, val message: String) {
        override fun toString() = "$rule (${count}x): $message"
    }

    private fun violations(pdf: ByteArray, flavour: PDFAFlavour): List<Violation> {
        val foundry = Foundries.defaultInstance()
        foundry.createParser(ByteArrayInputStream(pdf), flavour).use { parser ->
            foundry.createValidator(flavour, false).use { validator ->
                return validator.validate(parser).testAssertions
                    .filter { it.status == TestAssertion.Status.FAILED }
                    .groupBy { it.ruleId }
                    .map { (rule, failures) ->
                        Violation(
                            rule = "${rule.specification} ${rule.clause}-${rule.testNumber}",
                            count = failures.size,
                            message = "${failures.first().message} [${failures.first().locationContext}]",
                        )
                    }
            }
        }
    }

    // --- corpus ---------------------------------------------------------------------------------

    private fun registry(): ObjectNode = javaClass.getResourceAsStream(REGISTRY)
        .let { requireNotNull(it) { "$REGISTRY is not on the classpath" } }
        .use { json.readTree(it) as ObjectNode }

    /** Every registry example as a renderable document, named `<block type>/<example name>`. */
    private fun registryExamples(): List<Pair<String, TemplateDocument>> = registry().get("components").values().flatMap { component ->
        val type = component.get("type").asString()
        ((component.get("examples") as? ArrayNode)?.values() ?: emptyList()).map { example ->
            "$type/${example.get("name").asString()}" to wrap(type, example.get("fragment") as ObjectNode)
        }
    }

    /**
     * A fragment becomes a document: a root example already is one; anything else gets a synthetic
     * root, with nodes the fragment root does not reach attached beside it, and a placeholder gets
     * the stencil ancestor it needs. Same wrapping as the MCP `ExampleRenderingIntegrationTest`.
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
            allNodes.set(STENCIL, node(STENCIL, "stencil", STENCIL_SLOT).set("props", json.createObjectNode().put("stencilKey", "conformance")))
            allSlots.set(STENCIL_SLOT, slot(STENCIL_SLOT, STENCIL, tops))
            listOf(STENCIL)
        } else {
            tops
        }
        allNodes.set(ROOT, node(ROOT, "root", ROOT_SLOT))
        allSlots.set(ROOT_SLOT, slot(ROOT_SLOT, ROOT, rootChildren))
        return document(ROOT, allNodes, allSlots)
    }

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

    companion object {
        private const val FIGURE_WITHOUT_ALT = "ISO 14289-1:2014 7.3-1"
        private const val FONT_NOT_EMBEDDED = "ISO 14289-1:2014 7.21.4.1-1"
        private const val HEADINGS_START_AT_H1 = "ISO 14289-1:2014 7.4.2-1"

        /**
         * Violations reported today, per registry example, each with where it gets fixed. The test
         * fails when one disappears, so fixing it means deleting its line.
         */
        private val KNOWN_VIOLATIONS: Map<String, Set<String>> = mapOf(
            // The renderer tags a QR code as a Figure without alternative text (#1307).
            "qrcode/data-bound-link" to setOf(FIGURE_WITHOUT_ALT),
            "qrcode/static-string" to setOf(FIGURE_WITHOUT_ALT),
            // The example's text starts at a level-2 heading: the author's choice, not the renderer's,
            // which the heading-level quality rule reports (#1306). Fix the example in epistola-contract.
            "container/with-children" to setOf(HEADINGS_START_AT_H1),
        )

        private const val REGISTRY = "/META-INF/epistola-catalog/component-registry.json"
        private const val ROOT = "n-conformance-root"
        private const val ROOT_SLOT = "s-conformance-root"
        private const val STENCIL = "n-conformance-stencil"
        private const val STENCIL_SLOT = "s-conformance-stencil"

        private val pngBytes: ByteArray = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()

        init {
            VeraGreenfieldFoundryProvider.initialise()
        }
    }
}

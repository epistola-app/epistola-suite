// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.generation.testing.RegistryExamples
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
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates rendered output with veraPDF, the reference validator, against the conformance a PDF/A
 * document claims: PDF/UA-1 and PDF/A-2b (#453). Without PDF/A a document claims neither (#1308);
 * those renders are checked for complete tagging instead.
 *
 * The corpus is every example in the contract's component registry, the same examples the editor
 * requires per block and the MCP server serves, so a new block is covered the day it gets its
 * example ([RegistryExamples], shared with the MCP rendering test). Synthetic documents add what
 * the examples do not exercise. This checks the renderer,
 * not authors: it proves the node-to-tag mapping is valid. Problems an author causes, such as an
 * image without alt text, are the quality checks' business; [an image without alt text fails] keeps
 * this test able to tell the difference.
 */
class PdfConformanceTest {

    private val renderer = DirectPdfRenderer()

    private val assets = AssetResolver { _, _ -> AssetResolution(pngBytes, "image/png") }

    private val sampleData = RegistryExamples.sampleData

    @TestFactory
    fun `every component example conforms with PDF-A on`(): List<DynamicTest> = registryExamples().map { (name, document) ->
        dynamicTest(name) {
            assertConforms(render(document, pdfa = true), expected = KNOWN_VIOLATIONS[name].orEmpty())
        }
    }

    /**
     * Without PDF/A nothing is claimed: the standard fonts are not embedded, which PDF/UA-1 requires
     * (#1308). The tagging is the same code, so the render must still leave no content untagged.
     */
    @TestFactory
    fun `every component example without PDF-A claims nothing and is fully tagged`(): List<DynamicTest> = registryExamples().map { (name, document) ->
        dynamicTest(name) {
            val pdf = render(document, pdfa = false)
            assertTrue("pdfuaid" !in PdfAccessibilityInspector.inspect(pdf).xmp, "no PDF/UA-1 identifier without PDF/A")
            assertEquals(emptyList(), PdfMarkedContentInspector.unmarkedText(pdf), "content neither tagged nor an artifact")
        }
    }

    @Test
    fun `a preview watermark conforms`() {
        val document = registryExamples().first { it.first == "text/minimal" }.second
        assertConforms(render(document, pdfa = true, watermarkText = "PREVIEW"))
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
        assertConforms(render(document, pdfa = true))
    }

    @Test
    fun `a themed document with a custom font family, a style preset and an SVG image conforms`() {
        // The registry examples render without a theme, with the built-in font and a PNG. Custom fonts
        // are what PDF/A and PDF/UA are strictest about (embedding, widths, Unicode maps).
        val heading = mapOf("type" to "heading", "attrs" to mapOf("level" to 1), "content" to listOf(mapOf("type" to "text", "text" to "Decision on your application")))
        val paragraph = mapOf(
            "type" to "paragraph",
            "content" to listOf(
                mapOf("type" to "text", "text" to "Dear Jane, we have "),
                mapOf("type" to "text", "text" to "approved", "marks" to listOf(mapOf("type" to "bold"))),
                mapOf("type" to "text", "text" to " your application. Ça va, Zoë?", "marks" to listOf(mapOf("type" to "italic"))),
            ),
        )
        val nodes = listOf(
            Node(id = "n-text", type = "text", props = mapOf("content" to mapOf("type" to "doc", "content" to listOf(heading, paragraph)))),
            Node(id = "n-label", type = "text", stylePreset = "label", props = mapOf("content" to mapOf("type" to "doc", "content" to listOf(mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to "Reference 2026-001"))))))),
            Node(id = "n-logo", type = "image", props = mapOf("imageKey" to "logo-svg", "alt" to "Municipal logo", "width" to "60pt", "height" to "30pt")),
        )
        val document = TemplateDocument(
            root = "n-root",
            nodes = mapOf("n-root" to Node(id = "n-root", type = "root", slots = listOf("s-root"))) + nodes.associateBy { it.id },
            slots = mapOf("s-root" to Slot(id = "s-root", nodeId = "n-root", name = "children", children = nodes.map { it.id })),
        )
        val theme = ResolvedTheme(
            documentStyles = mapOf("fontFamily" to mapOf("slug" to "house-sans", "catalogKey" to "acme"), "color" to "#1a1a1a"),
            blockStylePresets = mapOf("label" to mapOf("label" to "Label", "fontWeight" to 700, "color" to "#555555")),
        )
        val facesAsked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val fonts = FontFamilyResolver { _, slug, weight, italic ->
            facesAsked += "$slug $weight${if (italic) " italic" else ""}"
            FontFaceBytes(fontFace(weight, italic), contentHash = null)
        }
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="60" height="30"><rect width="60" height="30" fill="#004488"/></svg>"""
        var svgResolved = false
        val svgAssets = AssetResolver { id, _ ->
            if (id == "logo-svg") AssetResolution(svg.toByteArray(), "image/svg+xml").also { svgResolved = true } else null
        }

        assertConforms(render(document, pdfa = true, theme = theme, fonts = fonts, assetResolver = svgAssets))
        assertTrue(svgResolved, "the SVG image was rendered")
        assertTrue(setOf("house-sans 400", "house-sans 700", "house-sans 400 italic").all { it in facesAsked }, "faces asked: $facesAsked")
    }

    @Test
    fun `a datatable, a list and a loop that run over several pages conform`() {
        val many = sampleData + mapOf(
            "items" to (1..120).map { mapOf("description" to "Line item $it", "quantity" to it, "amount" to it * 1.25) },
            "features" to (1..150).map { "Feature $it" },
            "attendees" to (1..150).map { mapOf("name" to "Attendee $it", "email" to "a$it@example.com") },
            "steps" to (1..150).map { mapOf("title" to "Step $it") },
        )
        for (name in listOf("datatable/invoice-line-items", "datalist/bulleted-list", "datalist/numbered-list", "loop/over-array")) {
            val pdf = render(example(name), pdfa = true, data = many)
            assertTrue(pageCount(pdf) > 1, "$name should run over several pages")
            assertConforms(pdf)
        }
    }

    @Test
    fun `a datatable, a list and a loop over nothing conform`() {
        val none = sampleData + mapOf("items" to emptyList<Any>(), "features" to emptyList<Any>(), "attendees" to emptyList<Any>())
        for (name in listOf("datatable/invoice-line-items", "datalist/bulleted-list", "loop/over-array")) {
            assertConforms(render(example(name), pdfa = true, data = none))
        }
    }

    @Test
    fun `headers and footers per section conform`() {
        val doc = BandDoc()
        val document = doc.build(
            doc.text("title", "Annual report"),
            doc.header("h1", doc.text("h1-text", "FIRST SECTION HEADER")),
            doc.footer("f1", doc.text("f1-text", "Page footer")),
            *doc.body("first", 40).toTypedArray(),
            doc.pageBreak("break"),
            doc.header("h2", doc.text("h2-text", "SECOND SECTION HEADER")),
            *doc.body("second", 40).toTypedArray(),
        )
        val pdf = render(document, pdfa = true)
        assertTrue(pageCount(pdf) > 2, "the sections should span several pages")
        assertConforms(pdf)
    }

    @Test
    fun `nested stencils with styled containers conform`() {
        val doc = BandDoc()
        val built = doc.build(
            doc.stencil("outer", doc.container("box", doc.stencil("inner", doc.text("inner-text", "Inside two stencils")))),
        )
        val styled = built.copy(
            nodes = built.nodes.mapValues { (id, node) ->
                when (id) {
                    "box" -> node.copy(styles = mapOf("backgroundColor" to "#eef2f7", "padding" to "8pt", "color" to "#1a1a1a", "fontSize" to "11pt"))

                    // BandDoc names every stencil the same; a stencil inside itself would be recursion.
                    "inner" -> node.copy(props = node.props.orEmpty() + ("stencilKey" to "signature-block"))

                    else -> node
                }
            },
        )
        assertConforms(render(styled, pdfa = true))
    }

    @Test
    fun `a WebP image conforms`() {
        val image = Node(id = "n-webp", type = "image", props = mapOf("imageKey" to "photo", "alt" to "Photo of the town hall", "width" to "80pt", "height" to "80pt"))
        val document = TemplateDocument(
            root = "n-root",
            nodes = mapOf("n-root" to Node(id = "n-root", type = "root", slots = listOf("s-root")), image.id to image),
            slots = mapOf("s-root" to Slot(id = "s-root", nodeId = "n-root", name = "children", children = listOf(image.id))),
        )
        // 8x8 red WebP, as in ImageNodeRendererTest
        val webp = java.util.Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoIAAgAAkA4JaQAA3AA/vuUAAA=")
        assertConforms(render(document, pdfa = true, assetResolver = { _, _ -> AssetResolution(webp, "image/webp") }))
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

        val violations = violations(render(document, pdfa = true), PDFAFlavour.PDFUA_1)

        assertEquals(listOf(FIGURE_WITHOUT_ALT), violations.map { it.rule }, "only the figure rule should fail, got:\n${violations.joinToString("\n")}")
    }

    @Test
    fun `the corpus covers every block type in the registry`() {
        val covered = RegistryExamples.all().map { it.blockType }.toSet()
        assertEquals(RegistryExamples.blockTypes(), covered, "every block type needs at least one registry example")
    }

    // --- rendering ------------------------------------------------------------------------------

    private fun pageCount(pdf: ByteArray): Int = com.itextpdf.kernel.pdf.PdfDocument(com.itextpdf.kernel.pdf.PdfReader(ByteArrayInputStream(pdf))).use { it.numberOfPages }

    private fun render(
        document: TemplateDocument,
        pdfa: Boolean,
        data: Map<String, Any?> = sampleData,
        watermarkText: String? = null,
        theme: ResolvedTheme = ResolvedTheme(),
        fonts: FontFamilyResolver? = null,
        assetResolver: AssetResolver = assets,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        renderer.render(
            document = document,
            data = data,
            outputStream = out,
            // As production passes it: the template name and the tenant (DocumentGenerationExecutor).
            metadata = PdfMetadata(title = "Conformance letter", author = "Acme Corp"),
            resolvedTheme = theme,
            pdfaCompliant = pdfa,
            assetResolver = assetResolver,
            fontFamilyResolver = fonts,
            renderMode = RenderMode.STRICT,
            watermarkText = watermarkText,
        )
        return out.toByteArray()
    }

    // --- validation -----------------------------------------------------------------------------

    /**
     * Validates against PDF/UA-1 and PDF/A-2b. Fails on any violation not [expected], and on an
     * [expected] one that no longer occurs: a fixed violation has to leave [KNOWN_VIOLATIONS], so the
     * list only shrinks.
     */
    private fun assertConforms(pdf: ByteArray, expected: Set<String> = emptySet()) {
        val violations = listOf(PDFAFlavour.PDFUA_1, PDFAFlavour.PDFA_2_B).flatMap { violations(pdf, it) }
        val unexpected = violations.filter { it.rule !in expected }
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

    private fun registryExamples(): List<Pair<String, TemplateDocument>> = RegistryExamples.all().map { it.id to it.document }

    private fun example(name: String): TemplateDocument = RegistryExamples.named(name)

    companion object {
        private const val FIGURE_WITHOUT_ALT = "ISO 14289-1:2014 7.3-1"

        /**
         * None today. A violation that cannot be fixed in the change that reveals it goes here, per
         * registry example and with its issue; the test fails once it disappears, so the line goes
         * with the fix and the list only shrinks.
         */
        private val KNOWN_VIOLATIONS: Map<String, Set<String>> = emptyMap()

        private fun fontFace(weight: Int, italic: Boolean): ByteArray {
            val style = when {
                weight >= 600 && italic -> "BoldItalic"
                weight >= 600 -> "Bold"
                italic -> "Italic"
                else -> "Regular"
            }
            return requireNotNull(PdfConformanceTest::class.java.getResourceAsStream("/fonts/LiberationSans-$style.ttf")).use { it.readBytes() }
        }

        private val pngBytes: ByteArray = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()

        init {
            VeraGreenfieldFoundryProvider.initialise()
        }
    }
}

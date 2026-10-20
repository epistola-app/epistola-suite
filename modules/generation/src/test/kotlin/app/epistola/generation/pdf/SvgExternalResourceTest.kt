// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfStream
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Collections
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An SVG asset must never make the renderer fetch a URL or read a local file. Every vector
 * renders through the real [DirectPdfRenderer] against a local HTTP server; none may reach it,
 * and no image other than the SVG's own inline `data:` content may end up in the PDF.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD) // the request log is shared by every test in the class
class SvgExternalResourceTest {
    private val renderer = DirectPdfRenderer()
    private val hits = Collections.synchronizedList(mutableListOf<String>())
    private lateinit var server: HttpServer
    private lateinit var base: String

    @TempDir
    lateinit var tempDir: Path

    // Distinctive sizes, so an embedded image can be traced back to where it came from.
    private val remotePng = png(11, 17)
    private val localPng = png(13, 7)
    private val inlinePng = png(5, 3)

    @BeforeAll
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            hits += exchange.requestURI.toString()
            val path = exchange.requestURI.path
            val body = when {
                path.endsWith(".png") -> remotePng
                path.endsWith(".css") -> "rect{fill:blue}".toByteArray()
                path.endsWith(".svg") -> """<svg xmlns="http://www.w3.org/2000/svg"><rect id="a" width="5" height="5"/></svg>""".toByteArray()
                path.endsWith(".dtd") -> """<!ENTITY y "DTD_ENTITY_MARKER">""".toByteArray()
                else -> "HTTP_ENTITY_MARKER".toByteArray()
            }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterAll
    fun stopServer() {
        server.stop(0)
    }

    private fun vectors(): List<String> = listOf(
        "image-href-http",
        "image-xlink-href-http",
        "image-href-file-uri",
        "image-href-absolute-path",
        "image-href-local-svg",
        "css-import",
        "css-import-uppercase",
        "css-import-in-comment-split",
        "css-import-hex-escape",
        "css-import-cdata",
        "css-import-char-reference",
        "css-import-double-encoded",
        "css-import-namespaced-style",
        "css-import-after-rules",
        "link-stylesheet",
        "xml-stylesheet-instruction",
        "fe-image-href",
        "style-attribute-url",
        "css-font-face",
        "css-fill-url",
        "use-href-http",
        "xxe-file-entity",
        "xxe-http-entity",
        "external-dtd",
        "parameter-entity",
    )

    private fun svgFor(vector: String): String {
        val pngFile = Files.write(tempDir.resolve("local.png"), localPng)
        val secret = Files.writeString(tempDir.resolve("secret.txt"), "FILESECRETMARKER")
        val localSvg = Files.writeString(
            tempDir.resolve("inner.svg"),
            "<svg xmlns='http://www.w3.org/2000/svg' width='10' height='10'><text x='1' y='9'>LOCALSVGTEXT</text></svg>",
        )
        val ns = """xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="200" height="200""""
        return when (vector) {
            "image-href-http" -> """<svg $ns><image href="$base/a.png" width="50" height="50"/></svg>"""

            "image-xlink-href-http" -> """<svg $ns><image xlink:href="$base/b.png" width="50" height="50"/></svg>"""

            "image-href-file-uri" -> """<svg $ns><image href="${pngFile.toUri()}" width="50" height="50"/></svg>"""

            "image-href-absolute-path" -> """<svg $ns><image href="$pngFile" width="50" height="50"/></svg>"""

            "image-href-local-svg" -> """<svg $ns><image href="${localSvg.toUri()}" width="50" height="50"/></svg>"""

            "css-import" -> """<svg $ns><style>@import url($base/c.css);</style><rect width="10" height="10"/></svg>"""

            "css-import-uppercase" -> """<svg $ns><style>@IMPORT URL($base/c.css);</style><rect width="10" height="10"/></svg>"""

            "css-import-in-comment-split" -> """<svg $ns><style>@im/**/port url($base/c.css);</style><rect width="10" height="10"/></svg>"""

            "css-import-hex-escape" -> """<svg $ns><style>@\69mport url($base/c.css);</style><rect width="10" height="10"/></svg>"""

            "css-import-cdata" -> """<svg $ns><style><![CDATA[@import url($base/c.css);]]></style><rect width="10" height="10"/></svg>"""

            "css-import-char-reference" -> """<svg $ns><style>&#64;import url($base/c.css);</style><rect width="10" height="10"/></svg>"""

            "css-import-double-encoded" ->
                """<svg $ns><style>@import url($base/c.css); &amp;#64;import url($base/d.css);</style><rect width="10" height="10"/></svg>"""

            "css-import-namespaced-style" ->
                """<svg:svg xmlns:svg="http://www.w3.org/2000/svg" width="200" height="200"><svg:style>@import url($base/c.css);</svg:style></svg:svg>"""

            "css-import-after-rules" -> """<svg $ns><style>rect{fill:red} @import url($base/c.css);</style><rect width="10" height="10"/></svg>"""

            "link-stylesheet" -> """<svg $ns><link rel="stylesheet" href="$base/c.css"/><rect width="10" height="10"/></svg>"""

            "xml-stylesheet-instruction" ->
                """<?xml version="1.0"?><?xml-stylesheet type="text/css" href="$base/c.css"?><svg $ns><rect width="10" height="10"/></svg>"""

            "fe-image-href" ->
                """<svg $ns><filter id="f"><feImage href="$base/h.png"/></filter><rect width="10" height="10" filter="url(#f)"/></svg>"""

            "style-attribute-url" -> """<svg $ns><text x="10" y="20" style="font-family:X;src:url($base/i.ttf)">hi</text></svg>"""

            "css-font-face" ->
                """<svg $ns><style>@font-face{font-family:X;src:url($base/f.ttf)} text{font-family:X}</style><text x="10" y="20">hi</text></svg>"""

            "css-fill-url" -> """<svg $ns><rect width="10" height="10" style="fill:url($base/g.svg#a)"/></svg>"""

            "use-href-http" -> """<svg $ns><use href="$base/e.svg#a"/></svg>"""

            "xxe-file-entity" ->
                """<?xml version="1.0"?><!DOCTYPE svg [<!ENTITY x SYSTEM "${secret.toUri()}">]><svg $ns><text x="10" y="20">&x;</text></svg>"""

            "xxe-http-entity" ->
                """<?xml version="1.0"?><!DOCTYPE svg [<!ENTITY x SYSTEM "$base/entity">]><svg $ns><text x="10" y="20">&x;</text></svg>"""

            "external-dtd" ->
                """<?xml version="1.0"?><!DOCTYPE svg SYSTEM "$base/d.dtd"><svg $ns><text x="10" y="20">&y;</text></svg>"""

            "parameter-entity" ->
                """<?xml version="1.0"?><!DOCTYPE svg [<!ENTITY % p SYSTEM "$base/p.dtd"> %p;]><svg $ns><text x="10" y="20">&y;</text></svg>"""

            else -> error("unknown vector $vector")
        }
    }

    @TestFactory
    fun `SVG in the body never reaches an external resource`(): List<DynamicTest> = vectors().map { vector ->
        DynamicTest.dynamicTest(vector) { assertNothingEscapes(vector, bodyDocument()) }
    }

    @TestFactory
    fun `SVG in a page header never reaches an external resource`(): List<DynamicTest> = vectors().map { vector ->
        DynamicTest.dynamicTest(vector) { assertNothingEscapes(vector, headerDocument()) }
    }

    @Test
    fun `inline data image, shapes and text in an SVG still render`() {
        val dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(inlinePng)
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="200" height="100">
              <defs><linearGradient id="g"><stop offset="0" stop-color="red"/><stop offset="1" stop-color="blue"/></linearGradient></defs>
              <rect id="box" width="40" height="40" fill="url(#g)"/>
              <use href="#box" x="50"/>
              <circle cx="120" cy="20" r="10" fill="green"/>
              <image href="$dataUri" x="150" y="0" width="20" height="12"/>
              <image xlink:href="$dataUri" x="150" y="20" width="20" height="12"/>
              <text x="10" y="80" font-family="Helvetica">INLINESVGTEXT</text>
            </svg>
        """.trimIndent()

        val pdf = render(svg, bodyDocument())

        val images = imageSizes(pdf)
        assertTrue("5x3" in images, "the inline data: image must be embedded, got $images")
        assertTrue(PdfContentExtractor.extract(pdf).contains("INLINESVGTEXT"), "SVG text must still render")
    }

    @Test
    fun `an SVG whose style imports keeps its other rules`() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" width="100" height="40">
              <style>@import url(https://example.invalid/x.css); text { font-family: Courier; }</style>
              <text x="5" y="20">KEPTSTYLETEXT</text>
            </svg>
        """.trimIndent()
        val rewritten = String(SafeSvgConversion.withoutCssImports(svg.toByteArray()))
        assertTrue("@import" !in rewritten, rewritten)
        assertTrue("font-family: Courier" in rewritten, rewritten)
        assertTrue(PdfContentExtractor.extract(render(svg, bodyDocument())).contains("KEPTSTYLETEXT"))
    }

    @Test
    fun `an SVG without imports is passed to iText byte for byte`() {
        val svg = """<?xml version="1.0" encoding="UTF-8"?><svg xmlns="http://www.w3.org/2000/svg"><style>rect{fill:red}</style></svg>""".toByteArray()
        assertTrue(svg.contentEquals(SafeSvgConversion.withoutCssImports(svg)))
    }

    private fun assertNothingEscapes(vector: String, document: TemplateDocument) {
        hits.clear()
        val pdf = runCatching { render(svgFor(vector), document) }.getOrNull()
        // A refused resource may make iText drop the element or fail the SVG; both are fine.
        // What is not fine is a request reaching the server or a foreign image in the PDF.
        assertEquals(emptyList(), hits.toList(), "[$vector] the renderer made HTTP requests")
        if (pdf != null) {
            val images = imageSizes(pdf)
            assertTrue(images.none { it == "13x7" || it == "11x17" }, "[$vector] an external image was embedded: $images")
            val raw = String(pdf, Charsets.ISO_8859_1) + decodedStreams(pdf)
            for (marker in listOf("FILESECRETMARKER", "HTTP_ENTITY_MARKER", "DTD_ENTITY_MARKER")) {
                assertTrue(marker !in raw, "[$vector] $marker leaked into the PDF")
            }
            assertTrue("LOCALSVGTEXT" !in PdfContentExtractor.extract(pdf), "[$vector] a local SVG file was rendered")
        }
    }

    private fun render(svg: String, document: TemplateDocument): ByteArray {
        val out = ByteArrayOutputStream()
        renderer.render(
            document,
            emptyMap(),
            out,
            assetResolver = { _, _ -> AssetResolution(content = svg.toByteArray(), mimeType = "image/svg+xml") },
            renderMode = RenderMode.PREVIEW,
        )
        return out.toByteArray()
    }

    private fun bodyDocument(): TemplateDocument = TemplateDocument(
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "image" to Node(id = "image", type = "image", props = mapOf("imageKey" to "svg")),
        ),
        slots = mapOf("root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("image"))),
    )

    private fun headerDocument(): TemplateDocument = TemplateDocument(
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "header" to Node(id = "header", type = "pageheader", slots = listOf("header-slot"), props = mapOf("height" to "60pt")),
            "image" to Node(id = "image", type = "image", props = mapOf("imageKey" to "svg")),
        ),
        slots = mapOf(
            "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("header")),
            "header-slot" to Slot(id = "header-slot", nodeId = "header", name = "children", children = listOf("image")),
        ),
    )

    private fun imageSizes(pdf: ByteArray): List<String> = inspect(pdf).first

    private fun decodedStreams(pdf: ByteArray): String = inspect(pdf).second

    /** Sizes of every image XObject in the PDF, and the decoded text of every other stream. */
    private fun inspect(pdf: ByteArray): Pair<List<String>, String> {
        val document = PdfDocument(PdfReader(ByteArrayInputStream(pdf)))
        try {
            val sizes = mutableListOf<String>()
            val text = StringBuilder()
            for (number in 1 until document.numberOfPdfObjects) {
                val stream = document.getPdfObject(number) as? PdfStream ?: continue
                if (stream.getAsName(PdfName.Subtype) == PdfName.Image) {
                    sizes += "${stream.getAsNumber(PdfName.Width)}x${stream.getAsNumber(PdfName.Height)}"
                } else {
                    runCatching { text.append(String(stream.getBytes(true), Charsets.ISO_8859_1)) }
                }
            }
            return sizes to text.toString()
        } finally {
            document.close()
        }
    }

    private fun png(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
}

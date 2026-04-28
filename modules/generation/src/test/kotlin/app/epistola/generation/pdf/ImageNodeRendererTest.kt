package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImageNodeRendererTest {

    private val renderer = DirectPdfRenderer()

    /** Minimal valid 1x1 PNG (67 bytes). */
    private val testPngBytes: ByteArray = run {
        val header = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
        val ihdr = byteArrayOf(
            0x00, 0x00, 0x00, 0x0D,
            0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x01,
            0x08, 0x02,
            0x00, 0x00, 0x00,
            0x90.toByte(), 0x77, 0x53, 0xDE.toByte(),
        )
        val idat = byteArrayOf(
            0x00, 0x00, 0x00, 0x0C,
            0x49, 0x44, 0x41, 0x54,
            0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(),
            0xCF.toByte(), 0xC0.toByte(), 0x00, 0x00,
            0x00, 0x02, 0x00, 0x01,
            0xE2.toByte(), 0x21, 0xBC.toByte(), 0x33,
        )
        val iend = byteArrayOf(
            0x00, 0x00, 0x00, 0x00,
            0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
        header + ihdr + idat + iend
    }

    private val testJpegBytes: ByteArray = run {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, 0xFF0000)
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "jpeg", output)
        output.toByteArray()
    }

    private fun documentWithImageNode(
        imageProps: Map<String, Any?>,
    ): TemplateDocument {
        val rootNodeId = "root-1"
        val rootSlotId = "slot-root"
        val imageNodeId = "image-1"

        return TemplateDocument(
            root = rootNodeId,
            nodes = mapOf(
                rootNodeId to Node(id = rootNodeId, type = "root", slots = listOf(rootSlotId)),
                imageNodeId to Node(id = imageNodeId, type = "image", props = imageProps),
            ),
            slots = mapOf(
                rootSlotId to Slot(
                    id = rootSlotId,
                    nodeId = rootNodeId,
                    name = "children",
                    children = listOf(imageNodeId),
                ),
            ),
        )
    }

    private val resolverWithTestPng = AssetResolver { assetId ->
        if (assetId == "asset-123") {
            AssetResolution(content = testPngBytes, mimeType = "image/png")
        } else {
            null
        }
    }

    private fun assertRenderedPdf(
        document: TemplateDocument,
        resolver: AssetResolver?,
        renderMode: RenderMode = RenderMode.STRICT,
    ) {
        val output = ByteArrayOutputStream()
        renderer.render(document, emptyMap(), output, assetResolver = resolver, renderMode = renderMode)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `renders PNG image asset`() {
        val document = documentWithImageNode(mapOf("assetId" to "asset-123"))
        assertRenderedPdf(document, resolverWithTestPng)
    }

    @Test
    fun `renders JPEG image asset`() {
        val resolver = AssetResolver { assetId ->
            if (assetId == "asset-jpeg") {
                AssetResolution(content = testJpegBytes, mimeType = "image/jpeg")
            } else {
                null
            }
        }
        val document = documentWithImageNode(mapOf("assetId" to "asset-jpeg"))
        assertRenderedPdf(document, resolver)
    }

    @Test
    fun `renders image with pixel dimensions`() {
        val document = documentWithImageNode(
            mapOf("assetId" to "asset-123", "width" to "200px", "height" to "100px"),
        )
        assertRenderedPdf(document, resolverWithTestPng)
    }

    @Test
    fun `renders image with percentage width`() {
        val document = documentWithImageNode(
            mapOf("assetId" to "asset-123", "width" to "50%"),
        )
        assertRenderedPdf(document, resolverWithTestPng)
    }

    @Test
    fun `skips gracefully when no assetId`() {
        val document = documentWithImageNode(mapOf("alt" to "placeholder"))
        assertRenderedPdf(document, resolverWithTestPng)
    }

    @Test
    fun `skips gracefully when asset not found`() {
        val document = documentWithImageNode(mapOf("assetId" to "non-existent"))
        assertRenderedPdf(document, resolverWithTestPng)
    }

    @Test
    fun `skips gracefully when no resolver`() {
        val document = documentWithImageNode(mapOf("assetId" to "asset-123"))
        assertRenderedPdf(document, null)
    }

    @Test
    fun `renders SVG image asset`() {
        val svgBytes = """
            <svg xmlns="http://www.w3.org/2000/svg" width="10" height="10">
              <rect width="10" height="10" fill="red" />
            </svg>
        """.trimIndent().toByteArray()
        val resolver = AssetResolver { assetId ->
            if (assetId == "asset-svg") {
                AssetResolution(content = svgBytes, mimeType = "image/svg+xml")
            } else {
                null
            }
        }
        val document = documentWithImageNode(mapOf("assetId" to "asset-svg"))
        assertRenderedPdf(document, resolver)
    }

    @Test
    fun `renders WEBP image asset`() {
        // 8x8 red pixel WEBP
        val webpBytes = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoIAAgAAkA4JaQAA3AA/vuUAAA=")
        val resolver = AssetResolver { assetId ->
            if (assetId == "asset-webp") {
                AssetResolution(content = webpBytes, mimeType = "image/webp")
            } else {
                null
            }
        }
        val document = documentWithImageNode(mapOf("assetId" to "asset-webp"))
        assertRenderedPdf(document, resolver)
    }

    // -- RenderMode.STRICT: corrupt assets throw --

    @Test
    fun `strict mode throws on corrupt PNG`() {
        val resolver = AssetResolver { AssetResolution(content = byteArrayOf(0, 1, 2, 3), mimeType = "image/png") }
        val document = documentWithImageNode(mapOf("assetId" to "bad"))
        assertFailsWith<ImageRenderException> {
            assertRenderedPdf(document, resolver, RenderMode.STRICT)
        }
    }

    @Test
    fun `strict mode throws on malformed SVG`() {
        val resolver = AssetResolver { AssetResolution(content = "not svg".toByteArray(), mimeType = "image/svg+xml") }
        val document = documentWithImageNode(mapOf("assetId" to "bad"))
        assertFailsWith<ImageRenderException> {
            assertRenderedPdf(document, resolver, RenderMode.STRICT)
        }
    }

    @Test
    fun `strict mode throws on corrupt WEBP`() {
        val resolver = AssetResolver { AssetResolution(content = byteArrayOf(0, 1, 2, 3), mimeType = "image/webp") }
        val document = documentWithImageNode(mapOf("assetId" to "bad"))
        assertFailsWith<ImageRenderException> {
            assertRenderedPdf(document, resolver, RenderMode.STRICT)
        }
    }

    // -- RenderMode.PREVIEW: corrupt assets render placeholder --

    @Test
    fun `preview mode renders placeholder for corrupt PNG`() {
        val resolver = AssetResolver { AssetResolution(content = byteArrayOf(0, 1, 2, 3), mimeType = "image/png") }
        val document = documentWithImageNode(mapOf("assetId" to "bad"))
        assertRenderedPdf(document, resolver, RenderMode.PREVIEW)
    }

    @Test
    fun `preview mode renders placeholder for malformed SVG`() {
        val resolver = AssetResolver { AssetResolution(content = "not svg".toByteArray(), mimeType = "image/svg+xml") }
        val document = documentWithImageNode(mapOf("assetId" to "bad"))
        assertRenderedPdf(document, resolver, RenderMode.PREVIEW)
    }

    @Test
    fun `preview mode renders placeholder for corrupt WEBP`() {
        val resolver = AssetResolver { AssetResolution(content = byteArrayOf(0, 1, 2, 3), mimeType = "image/webp") }
        val document = documentWithImageNode(mapOf("assetId" to "bad"))
        assertRenderedPdf(document, resolver, RenderMode.PREVIEW)
    }
}

package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import java.io.ByteArrayOutputStream
import kotlin.test.Test
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

    @Test
    fun `renders image node with valid asset`() {
        val document = documentWithImageNode(mapOf("assetId" to "asset-123"))

        val output = ByteArrayOutputStream()
        renderer.render(document, emptyMap(), output, assetResolver = resolverWithTestPng)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `renders image with pixel dimensions`() {
        val document = documentWithImageNode(
            mapOf("assetId" to "asset-123", "width" to "200px", "height" to "100px"),
        )

        val output = ByteArrayOutputStream()
        renderer.render(document, emptyMap(), output, assetResolver = resolverWithTestPng)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `renders image with percentage width`() {
        val document = documentWithImageNode(
            mapOf("assetId" to "asset-123", "width" to "50%"),
        )

        val output = ByteArrayOutputStream()
        renderer.render(document, emptyMap(), output, assetResolver = resolverWithTestPng)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `skips gracefully when no assetId`() {
        val document = documentWithImageNode(mapOf("alt" to "placeholder"))

        val output = ByteArrayOutputStream()
        renderer.render(document, emptyMap(), output, assetResolver = resolverWithTestPng)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `skips gracefully when asset not found`() {
        val document = documentWithImageNode(mapOf("assetId" to "non-existent"))

        val output = ByteArrayOutputStream()
        renderer.render(document, emptyMap(), output, assetResolver = resolverWithTestPng)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `skips gracefully when no resolver`() {
        val document = documentWithImageNode(mapOf("assetId" to "asset-123"))

        val output = ByteArrayOutputStream()
        renderer.render(document, emptyMap(), output, assetResolver = null)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }
}

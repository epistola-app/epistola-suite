package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

class QrCodeNodeRendererTest {

    private val renderer = DirectPdfRenderer()

    private fun documentWithQrCodeNode(qrProps: Map<String, Any?>): TemplateDocument {
        val rootNodeId = "root-1"
        val rootSlotId = "slot-root"
        val qrNodeId = "qr-1"

        return TemplateDocument(
            root = rootNodeId,
            nodes = mapOf(
                rootNodeId to Node(id = rootNodeId, type = "root", slots = listOf(rootSlotId)),
                qrNodeId to Node(id = qrNodeId, type = "qrcode", props = qrProps),
            ),
            slots = mapOf(
                rootSlotId to Slot(
                    id = rootSlotId,
                    nodeId = rootNodeId,
                    name = "children",
                    children = listOf(qrNodeId),
                ),
            ),
        )
    }

    @Test
    fun `renders QR code node from string expression`() {
        val document = documentWithQrCodeNode(
            mapOf(
                "value" to mapOf("raw" to "customer.paymentLink", "language" to "jsonata"),
                "size" to "96pt",
            ),
        )

        val output = ByteArrayOutputStream()
        renderer.render(
            document,
            mapOf("customer" to mapOf("paymentLink" to "https://example.com/pay/123")),
            output,
        )

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `renders QR code node from numeric expression`() {
        val document = documentWithQrCodeNode(
            mapOf(
                "value" to mapOf("raw" to "invoiceNumber", "language" to "jsonata"),
            ),
        )

        val output = ByteArrayOutputStream()
        renderer.render(document, mapOf("invoiceNumber" to 12345), output)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `skips gracefully when expression is missing`() {
        val document = documentWithQrCodeNode(emptyMap())

        val output = ByteArrayOutputStream()
        renderer.render(document, emptyMap(), output)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `renders QR code node from boolean expression`() {
        val document = documentWithQrCodeNode(
            mapOf(
                "value" to mapOf("raw" to "verified", "language" to "jsonata"),
            ),
        )

        val output = ByteArrayOutputStream()
        renderer.render(document, mapOf("verified" to true), output)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `skips gracefully when expression resolves to whitespace-only string`() {
        val document = documentWithQrCodeNode(
            mapOf(
                "value" to mapOf("raw" to "blank", "language" to "jsonata"),
            ),
        )

        val output = ByteArrayOutputStream()
        renderer.render(document, mapOf("blank" to "   "), output)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `skips gracefully when value exceeds QR capacity`() {
        val document = documentWithQrCodeNode(
            mapOf(
                "value" to mapOf("raw" to "huge", "language" to "jsonata"),
            ),
        )

        val output = ByteArrayOutputStream()
        renderer.render(document, mapOf("huge" to "x".repeat(5000)), output)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `renders with custom size in sp units`() {
        val document = documentWithQrCodeNode(
            mapOf(
                "value" to mapOf("raw" to "url", "language" to "jsonata"),
                "size" to "20sp",
            ),
        )

        val output = ByteArrayOutputStream()
        renderer.render(document, mapOf("url" to "https://example.com"), output)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `skips gracefully when expression resolves to object`() {
        val document = documentWithQrCodeNode(
            mapOf(
                "value" to mapOf("raw" to "customer", "language" to "jsonata"),
            ),
        )

        val output = ByteArrayOutputStream()
        renderer.render(document, mapOf("customer" to mapOf("name" to "Ada")), output)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }
}

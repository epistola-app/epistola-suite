package app.epistola.generation.pdf

import app.epistola.template.model.Expression
import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.IElement
import com.itextpdf.layout.element.Image
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** Thrown in [RenderMode.STRICT] when a QR code node cannot be rendered. */
class QrCodeRenderException(message: String, cause: Throwable) : RuntimeException(message, cause)

/**
 * Renders a "qrcode" node by evaluating its value expression and embedding the generated symbol.
 *
 * QR codes have a maximum data capacity that depends on the encoding mode and error correction level.
 * This renderer enforces a limit of [MAX_VALUE_BYTES] bytes (UTF-8 encoded) to stay safely within
 * the QR specification. Values exceeding this limit cause document generation to fail with an
 * [IllegalArgumentException].
 */
class QrCodeNodeRenderer : NodeRenderer {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun render(
        node: Node,
        document: TemplateDocument,
        context: RenderContext,
        registry: NodeRendererRegistry,
    ): List<IElement> {
        val expression = extractExpression(node.props?.get("value"), context.defaultExpressionLanguage)
            ?: return emptyList()

        val value = resolveQrValue(expression, context, node) ?: return emptyList()

        val valueBytes = value.toByteArray(Charsets.UTF_8).size
        require(valueBytes <= MAX_VALUE_BYTES) {
            "QR code node ${node.id} value is $valueBytes bytes, exceeding limit of $MAX_VALUE_BYTES bytes"
        }

        val sizePt = parseSizePt(node.props?.get("size"), context) ?: DEFAULT_SIZE_PT
        val qrType = (node.props?.get("qrType") as? String)?.lowercase() ?: QR_TYPE_STANDARD

        val logoImage = if (qrType == QR_TYPE_LOGO) {
            resolveLogoImage(node, context)
        } else {
            null
        }

        val pngBytes = try {
            generateQrCodePng(value, sizePt, qrType, logoImage)
        } catch (e: Exception) {
            logger.warn("Failed to render QR code node {}: {}", node.id, e.message)
            return when (context.renderMode) {
                RenderMode.STRICT -> throw QrCodeRenderException("Failed to render QR code node '${node.id}': ${e.message}", e)
                RenderMode.PREVIEW -> ErrorPlaceholder.render("QR code failed: ${e.message}")
            }
        }

        val image = Image(ImageDataFactory.create(pngBytes))
        image.setWidth(sizePt)
        image.setHeight(sizePt)

        val div = Div()
        StyleApplicator.applyStylesWithPreset(
            div,
            node.styles?.filterNonNullValues(),
            node.stylePreset,
            context.blockStylePresets,
            context.inheritedStyles,
            context.fontCache,
            context.renderingDefaults.componentDefaults("qrcode"),
            context.renderingDefaults.baseFontSizePt,
            context.spacingUnit,
        )
        div.add(image)

        return listOf(div)
    }

    private fun resolveQrValue(expression: Expression, context: RenderContext, node: Node): String? {
        val resolved = context.expressionEvaluator.evaluate(expression, context.effectiveData, context.loopContext)

        return when (resolved) {
            null -> {
                logger.debug("QR code node {} resolved to null, skipping", node.id)
                null
            }
            is String -> resolved.trim().takeIf { it.isNotEmpty() }
            is Number, is Boolean -> resolved.toString()
            else -> {
                logger.warn(
                    "QR code node {} resolved to unsupported type {}, skipping",
                    node.id,
                    resolved::class.simpleName,
                )
                null
            }
        }
    }

    private fun parseSizePt(value: Any?, context: RenderContext): Float? = when (value) {
        is Number -> value.toFloat().takeIf { it > 0f }
        is String -> {
            SpacingScale.parseSp(value, context.spacingUnit)?.let { return it.takeIf { size -> size > 0f } }
            when {
                value.endsWith("pt") -> value.removeSuffix("pt").toFloatOrNull()?.takeIf { it > 0f }
                else -> value.toFloatOrNull()?.takeIf { it > 0f }
            }
        }
        else -> null
    }

    private fun resolveLogoImage(node: Node, context: RenderContext): BufferedImage? {
        val assetId = node.props?.get("logoAssetId") as? String
        if (assetId.isNullOrBlank()) return null

        val resolver = context.assetResolver
            ?: throw IllegalArgumentException("Logo QR type requires an asset resolver")

        val resolution = resolver.resolve(assetId)
            ?: throw IllegalArgumentException("Logo asset not found: $assetId")

        if (resolution.mimeType == "image/svg+xml") {
            throw IllegalArgumentException("SVG logos are not supported for QR logo rendering")
        }

        return ByteArrayInputStream(resolution.content).use { logoStream ->
            ImageIO.read(logoStream)
        } ?: throw IllegalArgumentException("Failed to decode logo image asset: $assetId")
    }

    internal fun generateQrCodePng(value: String, sizePt: Float, qrType: String, logoImage: BufferedImage?): ByteArray {
        val targetPixelSize = maxOf(MIN_PIXEL_SIZE, sizePt.roundToInt())
        val errorCorrection = if (qrType == QR_TYPE_LOGO) ErrorCorrectionLevel.H else ErrorCorrectionLevel.L

        // Build deterministic QR modules first, then rasterize with a fixed quiet-zone size in modules.
        val qr = Encoder.encode(
            value,
            errorCorrection,
            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"),
        )
        val modules = qr.matrix
        val moduleCount = modules.width
        val totalModules = moduleCount + (QUIET_ZONE_MODULES * 2)
        val modulePixelSize = maxOf(1, targetPixelSize / totalModules)
        val rasterSize = totalModules * modulePixelSize

        val image = BufferedImage(rasterSize, rasterSize, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = java.awt.Color.WHITE
        graphics.fillRect(0, 0, rasterSize, rasterSize)
        graphics.color = java.awt.Color.BLACK

        for (moduleY in 0 until moduleCount) {
            for (moduleX in 0 until moduleCount) {
                if (modules[moduleX, moduleY].toInt() == 1) {
                    val x = (moduleX + QUIET_ZONE_MODULES) * modulePixelSize
                    val y = (moduleY + QUIET_ZONE_MODULES) * modulePixelSize
                    graphics.fillRect(x, y, modulePixelSize, modulePixelSize)
                }
            }
        }
        graphics.dispose()

        if (qrType == QR_TYPE_LOGO && logoImage != null) {
            overlayLogo(image, logoImage)
        }

        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        return output.toByteArray()
    }

    internal fun overlayLogo(baseQrImage: BufferedImage, logoImage: BufferedImage) {
        val logoSize = (baseQrImage.width * LOGO_SIZE_RATIO).roundToInt().coerceAtLeast(16)
        val x = (baseQrImage.width - logoSize) / 2
        val y = (baseQrImage.height - logoSize) / 2
        val framePadding = maxOf(2, (logoSize * LOGO_FRAME_PADDING_RATIO).roundToInt())

        val graphics = baseQrImage.createGraphics()
        graphics.color = java.awt.Color.WHITE
        graphics.fillRoundRect(
            x - framePadding,
            y - framePadding,
            logoSize + framePadding * 2,
            logoSize + framePadding * 2,
            LOGO_FRAME_RADIUS,
            LOGO_FRAME_RADIUS,
        )
        graphics.drawImage(logoImage, x, y, logoSize, logoSize, null)
        graphics.dispose()
    }

    private companion object {
        private const val DEFAULT_SIZE_PT = 120f
        private const val MIN_PIXEL_SIZE = 128
        private const val MAX_VALUE_BYTES = 2500
        private const val BLACK = -0x1000000
        private const val WHITE = -0x1
        private const val QR_TYPE_STANDARD = "standard"
        private const val QR_TYPE_LOGO = "logo"
        private const val LOGO_SIZE_RATIO = 0.22f
        private const val LOGO_FRAME_PADDING_RATIO = 0.06f
        private const val LOGO_FRAME_RADIUS = 8
        private const val QUIET_ZONE_MODULES = 4
    }
}

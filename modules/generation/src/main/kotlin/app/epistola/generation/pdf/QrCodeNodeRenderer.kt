package app.epistola.generation.pdf

import app.epistola.template.model.Expression
import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.IElement
import com.itextpdf.layout.element.Image
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Renders a "qrcode" node by evaluating its value expression and embedding the generated symbol.
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
        val sizePt = parseSizePt(node.props?.get("size"), context) ?: DEFAULT_SIZE_PT

        val image = Image(ImageDataFactory.create(generateQrCodePng(value, sizePt)))
        image.setWidth(sizePt)
        image.setHeight(sizePt)

        val div = Div()
        StyleApplicator.applyStylesWithPreset(
            div,
            node.styles?.filterNonNullValues(),
            node.stylePreset,
            context.blockStylePresets,
            context.documentStyles,
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

    private fun generateQrCodePng(value: String, sizePt: Float): ByteArray {
        val pixelSize = maxOf(MIN_PIXEL_SIZE, sizePt.roundToInt())
        val matrix = QRCodeWriter().encode(
            value,
            BarcodeFormat.QR_CODE,
            pixelSize,
            pixelSize,
            mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 1,
            ),
        )

        val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                image.setRGB(x, y, if (matrix.get(x, y)) BLACK else WHITE)
            }
        }

        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        return output.toByteArray()
    }

    private companion object {
        private const val DEFAULT_SIZE_PT = 120f
        private const val MIN_PIXEL_SIZE = 128
        private const val BLACK = -0x1000000
        private const val WHITE = -0x1
    }
}

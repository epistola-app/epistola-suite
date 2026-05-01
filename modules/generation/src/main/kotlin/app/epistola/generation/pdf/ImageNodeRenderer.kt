package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.IElement
import com.itextpdf.layout.element.Image
import com.itextpdf.layout.properties.UnitValue
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** Thrown in [RenderMode.STRICT] when an image asset cannot be decoded. */
class ImageRenderException(message: String, cause: Throwable) : RuntimeException(message, cause)

/**
 * Renders an "image" node to an iText Image element.
 *
 * Reads `assetId` from the node's props and uses the [AssetResolver] from the
 * [RenderContext] to load the image bytes. Gracefully skips if no resolver is
 * available, no assetId is specified, or the asset cannot be found.
 */
class ImageNodeRenderer(
    private val svgConverter: SvgImageConverter = SvgImageConverter.UNSUPPORTED,
) : NodeRenderer {

    /**
     * Converts raw SVG bytes to an iText [Image]. The conversion strategy is
     * injected so that [ImageNodeRenderer] stays independent of any specific
     * PDF library's SVG support (e.g., iText's `SvgConverter` requires a
     * `PdfDocument` that only the concrete renderer owns).
     */
    fun interface SvgImageConverter {
        fun convert(svgBytes: ByteArray): Image

        companion object {
            /** Default no-op converter that rejects SVG input. */
            val UNSUPPORTED = SvgImageConverter { throw UnsupportedOperationException("SVG rendering is not supported by this renderer") }
        }
    }

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun render(
        node: Node,
        document: TemplateDocument,
        context: RenderContext,
        registry: NodeRendererRegistry,
    ): List<IElement> {
        val props = node.props
        val assetId = props?.get("assetId") as? String
        if (assetId.isNullOrBlank()) {
            logger.debug("Image node {} has no assetId, skipping", node.id)
            return emptyList()
        }

        val resolver = context.assetResolver
        if (resolver == null) {
            logger.warn("No asset resolver available, cannot render image node {}", node.id)
            return emptyList()
        }

        val resolution = resolver.resolve(assetId)
        if (resolution == null) {
            logger.warn("Asset {} not found, skipping image node {}", assetId, node.id)
            return emptyList()
        }

        val image = try {
            createImageElement(resolution)
        } catch (e: Exception) {
            logger.warn("Failed to decode image asset {} ({}): {}", assetId, resolution.mimeType, e.message)
            return when (context.renderMode) {
                RenderMode.STRICT -> throw ImageRenderException(
                    "Failed to render image node '${node.id}' (asset=$assetId, type=${resolution.mimeType}): ${e.message}",
                    e,
                )
                RenderMode.PREVIEW -> ErrorPlaceholder.render("Image failed: ${e.message}")
            }
        }

        // Apply width/height from props
        val widthStr = props["width"] as? String
        val heightStr = props["height"] as? String
        val hasWidth = !widthStr.isNullOrBlank()
        val hasHeight = !heightStr.isNullOrBlank()
        val width = widthStr?.takeIf { it.isNotBlank() }
        val height = heightStr?.takeIf { it.isNotBlank() }

        val spacingUnit = context.spacingUnit
        when {
            hasWidth && hasHeight -> {
                applyDimension(requireNotNull(width), isWidth = true, image, spacingUnit)
                applyDimension(requireNotNull(height), isWidth = false, image, spacingUnit)
            }
            hasWidth -> {
                val widthValue = requireNotNull(width)
                applyDimension(widthValue, isWidth = true, image, spacingUnit)
                scaleProportionally(widthValue, isWidthGiven = true, image, spacingUnit)
            }
            hasHeight -> {
                val heightValue = requireNotNull(height)
                applyDimension(heightValue, isWidth = false, image, spacingUnit)
                scaleProportionally(heightValue, isWidthGiven = false, image, spacingUnit)
            }
            else -> {
                // No dimensions specified: auto-scale to fit available width
                image.setAutoScale(true)
            }
        }

        // Wrap in a Div to apply layout styles (padding, margin)
        val div = Div()
        StyleApplicator.applyStylesWithPreset(
            div,
            node.styles?.filterNonNullValues(),
            node.stylePreset,
            context.blockStylePresets,
            context.inheritedStyles,
            context.fontCache,
            context.renderingDefaults.componentDefaults("image"),
            context.renderingDefaults.baseFontSizePt,
            context.spacingUnit,
        )
        div.add(image)

        return listOf(div)
    }

    private fun applyDimension(value: String, isWidth: Boolean, image: Image, spacingUnit: Float) {
        if (value.endsWith("%")) {
            val percent = value.removeSuffix("%").toFloatOrNull() ?: return
            if (isWidth) {
                image.setWidth(UnitValue.createPercentValue(percent))
            } else {
                image.setHeight(UnitValue.createPercentValue(percent))
            }
        } else {
            val pt = parseToPt(value, spacingUnit) ?: return
            if (isWidth) {
                image.setWidth(pt)
            } else {
                image.setHeight(pt)
            }
        }
    }

    /**
     * When only one dimension is given as an absolute value, compute the
     * other from the image's intrinsic aspect ratio. Percentage values are left
     * to iText's layout engine.
     */
    private fun scaleProportionally(
        value: String,
        isWidthGiven: Boolean,
        image: Image,
        spacingUnit: Float,
    ) {
        if (value.endsWith("%")) return // let iText handle percentage layout

        val pt = parseToPt(value, spacingUnit) ?: return
        val intrinsicWidth = image.imageWidth
        val intrinsicHeight = image.imageHeight
        if (intrinsicWidth <= 0 || intrinsicHeight <= 0) return

        if (isWidthGiven) {
            val scaledHeight = pt * (intrinsicHeight / intrinsicWidth)
            image.setHeight(scaledHeight)
        } else {
            val scaledWidth = pt * (intrinsicWidth / intrinsicHeight)
            image.setWidth(scaledWidth)
        }
    }

    /** Parse a size value (pt, sp, or unitless) to points. */
    private fun parseToPt(value: String, spacingUnit: Float): Float? {
        SpacingScale.parseSp(value, spacingUnit)?.let { return it }
        return when {
            value.endsWith("pt") -> value.removeSuffix("pt").toFloatOrNull()
            else -> value.toFloatOrNull()
        }
    }

    private fun createImageElement(resolution: AssetResolution): Image = when (resolution.mimeType) {
        "image/svg+xml" -> svgConverter.convert(resolution.content)
        "image/webp" -> createWebpImage(resolution.content)
        else -> Image(ImageDataFactory.create(resolution.content))
    }

    private fun createWebpImage(content: ByteArray): Image {
        val bufferedImage = ByteArrayInputStream(content).use { webpStream ->
            ImageIO.read(webpStream)
        } ?: throw IllegalArgumentException("Failed to decode WEBP image content")
        val imageData = ImageDataFactory.create(bufferedImage, null)
        return Image(imageData)
    }
}

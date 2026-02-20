package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.IElement
import com.itextpdf.layout.element.Image
import com.itextpdf.layout.properties.UnitValue
import org.slf4j.LoggerFactory

/**
 * Renders an "image" node to an iText Image element.
 *
 * Reads `assetId` from the node's props and uses the [AssetResolver] from the
 * [RenderContext] to load the image bytes. Gracefully skips if no resolver is
 * available, no assetId is specified, or the asset cannot be found.
 */
class ImageNodeRenderer : NodeRenderer {

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

        val imageData = ImageDataFactory.create(resolution.content)
        val image = Image(imageData)

        // Apply width/height from props
        val widthStr = props["width"] as? String
        val heightStr = props["height"] as? String
        val hasWidth = !widthStr.isNullOrBlank()
        val hasHeight = !heightStr.isNullOrBlank()

        when {
            hasWidth && hasHeight -> {
                applyDimension(widthStr!!, isWidth = true, image)
                applyDimension(heightStr!!, isWidth = false, image)
            }
            hasWidth -> {
                // Scale proportionally: set width, compute height from aspect ratio
                applyDimension(widthStr!!, isWidth = true, image)
                scaleProportionally(widthStr, isWidthGiven = true, image, imageData)
            }
            hasHeight -> {
                // Scale proportionally: set height, compute width from aspect ratio
                applyDimension(heightStr!!, isWidth = false, image)
                scaleProportionally(heightStr, isWidthGiven = false, image, imageData)
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
            context.documentStyles,
            context.fontCache,
        )
        div.add(image)

        return listOf(div)
    }

    private fun applyDimension(value: String, isWidth: Boolean, image: Image) {
        if (value.endsWith("%")) {
            val percent = value.removeSuffix("%").toFloatOrNull() ?: return
            if (isWidth) {
                image.setWidth(UnitValue.createPercentValue(percent))
            } else {
                image.setHeight(UnitValue.createPercentValue(percent))
            }
        } else {
            val pt = parsePxToPoints(value) ?: return
            if (isWidth) {
                image.setWidth(pt)
            } else {
                image.setHeight(pt)
            }
        }
    }

    /**
     * When only one dimension is given as an absolute pixel value, compute the
     * other from the image's intrinsic aspect ratio. Percentage values are left
     * to iText's layout engine.
     */
    private fun scaleProportionally(
        value: String,
        isWidthGiven: Boolean,
        image: Image,
        imageData: com.itextpdf.io.image.ImageData,
    ) {
        if (value.endsWith("%")) return // let iText handle percentage layout

        val pt = parsePxToPoints(value) ?: return
        val intrinsicWidth = imageData.width // in points
        val intrinsicHeight = imageData.height // in points
        if (intrinsicWidth <= 0 || intrinsicHeight <= 0) return

        if (isWidthGiven) {
            val scaledHeight = pt * (intrinsicHeight / intrinsicWidth)
            image.setHeight(scaledHeight)
        } else {
            val scaledWidth = pt * (intrinsicWidth / intrinsicHeight)
            image.setWidth(scaledWidth)
        }
    }

    private fun parsePxToPoints(value: String): Float? {
        val px = value.removeSuffix("px").toFloatOrNull() ?: return null
        return px * 0.75f
    }
}

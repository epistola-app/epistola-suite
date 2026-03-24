package app.epistola.generation.html

import app.epistola.generation.filterNonNullValues
import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * Renders an "image" node to an HTML img element with base64 data URI.
 */
class HtmlImageNodeRenderer : HtmlNodeRenderer {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun render(
        node: Node,
        document: TemplateDocument,
        context: HtmlRenderContext,
        registry: HtmlNodeRendererRegistry,
    ): String {
        val props = node.props
        val assetId = props?.get("assetId") as? String
        if (assetId.isNullOrBlank()) {
            logger.debug("Image node {} has no assetId, skipping", node.id)
            return ""
        }

        val resolver = context.assetResolver
        if (resolver == null) {
            logger.warn("No asset resolver available, cannot render image node {}", node.id)
            return ""
        }

        val resolution = resolver.resolve(assetId)
        if (resolution == null) {
            logger.warn("Asset {} not found, skipping image node {}", assetId, node.id)
            return ""
        }

        val base64 = Base64.getEncoder().encodeToString(resolution.content)
        val dataUri = "data:${resolution.mimeType};base64,$base64"

        val imgStyle = buildImgStyle(props)
        val divStyle = HtmlStyleApplicator.buildStyleAttribute(
            node.styles?.filterNonNullValues(),
            node.stylePreset,
            context.blockStylePresets,
            context.documentStyles,
            context.renderingDefaults.componentDefaults("image"),
        )

        val imgTag = if (imgStyle.isNotEmpty()) {
            """<img src="$dataUri" style="$imgStyle" alt="">"""
        } else {
            """<img src="$dataUri" style="max-width: 100%" alt="">"""
        }

        return if (divStyle.isNotEmpty()) {
            """<div style="$divStyle">$imgTag</div>"""
        } else {
            "<div>$imgTag</div>"
        }
    }

    private fun buildImgStyle(props: Map<String, Any?>): String {
        val widthStr = props["width"] as? String
        val heightStr = props["height"] as? String
        val parts = mutableListOf<String>()

        if (!widthStr.isNullOrBlank()) {
            parts.add("width: $widthStr")
        }
        if (!heightStr.isNullOrBlank()) {
            parts.add("height: $heightStr")
        }
        if (parts.isEmpty()) {
            parts.add("max-width: 100%")
        }

        return parts.joinToString("; ")
    }
}

package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.IBlockElement
import com.itextpdf.layout.element.Image
import com.itextpdf.layout.layout.LayoutArea
import com.itextpdf.layout.layout.LayoutContext

/**
 * Shared composition + measurement for page header / footer bands.
 *
 * A page header/footer renders its slot children into a `Div` wrapper that
 * carries the node's own styles (borders, background, padding). The same
 * wrapper is used in two places that must agree byte-for-byte:
 *
 *  1. [PageHeaderEventHandler] / [PageFooterEventHandler] — draws it into the
 *     fixed band rectangle on every page.
 *  2. The pre-render measurement pass in [DirectPdfRenderer] — lays it out to
 *     discover the band's natural content height, so the reserved band is
 *     `max(configured height, content height)` and content is never clipped.
 *
 * Keeping the wrapper construction in one function guarantees the measured
 * height matches what is later rendered.
 */

/** Header margin sides consumed when positioning the band rectangle (not re-applied to the wrapper). */
internal val HEADER_CONSUMED_MARGINS = setOf("marginTop", "marginLeft", "marginRight")

/** Footer margin sides consumed when positioning the band rectangle. */
internal val FOOTER_CONSUMED_MARGINS = setOf("marginBottom", "marginLeft", "marginRight")

internal const val HEADER_COMPONENT_KEY = "pageheader"
internal const val FOOTER_COMPONENT_KEY = "pagefooter"

/**
 * A height so large no realistic header/footer can exceed it, used as the
 * vertical bound for the dry-layout measurement (≈ 35 m of content).
 */
private const val BAND_MEASURE_HEIGHT = 100_000f

/**
 * Builds the wrapper `Div` for a header/footer [node]: renders its slots with a
 * page-scoped context and applies the node's own styles (minus the margin sides
 * already consumed by the band rectangle).
 *
 * Mirrors exactly what the event handlers do: slot children render under
 * `withInheritedStylesFrom(node).withPageParams(...)`, while the wrapper's own
 * styles resolve against the *parent's* inherited styles ([baseContext]).
 */
internal fun buildBandWrapper(
    node: Node,
    document: TemplateDocument,
    baseContext: RenderContext,
    registry: NodeRendererRegistry,
    consumedMarginKeys: Set<String>,
    componentDefaultsKey: String,
    pageNumber: Int,
    totalPages: Int,
): Div {
    val childContext = baseContext.withInheritedStylesFrom(node).withPageParams(pageNumber, totalPages)
    val elements = registry.renderSlots(node, document, childContext)

    val wrapper = Div()
    val wrapperStyles = node.styleMapExcluding(consumedMarginKeys)
    StyleApplicator.applyStylesWithPreset(
        wrapper,
        wrapperStyles,
        node.stylePreset,
        baseContext.blockStylePresets,
        baseContext.inheritedStyles,
        baseContext.fontCache,
        baseContext.renderingDefaults.componentDefaults(componentDefaultsKey),
        baseContext.renderingDefaults.baseFontSizePt,
        baseContext.spacingUnit,
    )
    for (element in elements) {
        when (element) {
            is IBlockElement -> wrapper.add(element)
            is Image -> wrapper.add(element)
            is AreaBreak -> Unit
        }
    }
    return wrapper
}

/**
 * Lays out [wrapper] into a [width] × unbounded area attached to [iTextDocument]'s
 * renderer and returns the natural content height in points. This is a dry
 * layout — it draws nothing and adds no pages.
 */
internal fun measureBandContentHeight(wrapper: Div, iTextDocument: Document, width: Float): Float {
    val renderer = wrapper.createRendererSubTree().setParent(iTextDocument.renderer)
    renderer.layout(LayoutContext(LayoutArea(1, Rectangle(width, BAND_MEASURE_HEIGHT))))
    return renderer.occupiedArea?.bBox?.height ?: 0f
}

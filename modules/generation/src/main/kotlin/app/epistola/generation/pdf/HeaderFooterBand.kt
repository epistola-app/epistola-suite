// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfPage
import com.itextpdf.kernel.pdf.canvas.CanvasArtifact
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.layout.Canvas
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.IBlockElement
import com.itextpdf.layout.element.Image
import com.itextpdf.layout.layout.LayoutArea
import com.itextpdf.layout.layout.LayoutContext
import com.itextpdf.layout.properties.OverflowPropertyValue
import com.itextpdf.layout.properties.Property

/*
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

/**
 * Paints [headerNode] into the top band of [page], [headerHeight] points tall, as an artifact
 * so screen readers skip running content (WCAG PDF14). [context] carries the data scope the
 * header content renders with.
 */
internal fun paintHeaderBand(
    page: PdfPage,
    pdfDoc: PdfDocument,
    headerNode: Node,
    headerHeight: Float,
    document: TemplateDocument,
    context: RenderContext,
    registry: NodeRendererRegistry,
) {
    val pageSize = page.pageSize
    val pageNumber = pdfDoc.getPageNumber(page)

    // The header rectangle's distance to each page edge follows the cascade:
    // headerNode.margin{Top,Left,Right} → root.margin{Top,Left,Right} →
    // pageSettings.margins.{top,left,right} (template > theme > engine defaults).
    val topMargin = effectivePageMarginPt(headerNode, "marginTop", context)
    val leftMargin = effectivePageMarginPt(headerNode, "marginLeft", context)
    val rightMargin = effectivePageMarginPt(headerNode, "marginRight", context)

    // Rectangle y is measured from the bottom of the page.
    // For a header we place it at: pageTop - topMargin - headerHeight.
    val headerRect = Rectangle(
        pageSize.left + leftMargin,
        pageSize.top - topMargin - headerHeight,
        pageSize.width - leftMargin - rightMargin,
        headerHeight,
    )

    // Draw after normal page content (overlay)
    val pdfCanvas = PdfCanvas(page.newContentStreamAfter(), page.resources, pdfDoc)

    // Mark the running header as an artifact so screen readers skip it (WCAG PDF14)
    pdfCanvas.openTag(CanvasArtifact())

    // Constrain layout to the header rectangle
    val canvas = Canvas(pdfCanvas, headerRect)
    // Safety net: if a measurement edge case under-sizes the band, render the
    // overflow instead of silently dropping content.
    canvas.setProperty(Property.OVERFLOW_Y, OverflowPropertyValue.VISIBLE)
    canvas.setProperty(Property.OVERFLOW_X, OverflowPropertyValue.VISIBLE)

    val totalPages = context.totalPages ?: pdfDoc.numberOfPages
    val wrapper = buildBandWrapper(
        node = headerNode,
        document = document,
        baseContext = context,
        registry = registry,
        consumedMarginKeys = HEADER_CONSUMED_MARGINS,
        componentDefaultsKey = HEADER_COMPONENT_KEY,
        pageNumber = pageNumber,
        totalPages = totalPages,
    )
    canvas.add(wrapper)

    canvas.close()
    pdfCanvas.closeTag()
    pdfCanvas.release()
}

/**
 * Paints [footerNode] into the bottom band of [page], [footerHeight] points tall, as an
 * artifact. A footer with `hideOnFirstPage` keeps its band on page 1 but draws nothing there.
 */
internal fun paintFooterBand(
    page: PdfPage,
    pdfDoc: PdfDocument,
    footerNode: Node?,
    footerHeight: Float,
    document: TemplateDocument,
    context: RenderContext,
    registry: NodeRendererRegistry,
) {
    val pageSize = page.pageSize

    // The footer rectangle's distance to each page edge follows the cascade:
    // footerNode.margin{Bottom,Left,Right} → root.margin{Bottom,Left,Right} →
    // pageSettings.margins.{bottom,left,right} (template > theme > engine defaults).
    val bottomMargin = effectivePageMarginPt(footerNode, "marginBottom", context)
    val leftMargin = effectivePageMarginPt(footerNode, "marginLeft", context)
    val rightMargin = effectivePageMarginPt(footerNode, "marginRight", context)

    val footerRect = Rectangle(
        pageSize.left + leftMargin,
        pageSize.bottom + bottomMargin,
        pageSize.width - leftMargin - rightMargin,
        footerHeight,
    )

    // Write after normal page content
    val pdfCanvas = PdfCanvas(page.newContentStreamAfter(), page.resources, pdfDoc)

    // Mark the footer as an artifact so screen readers skip running content (WCAG PDF14).
    // try/finally guarantees the marked-content sequence is balanced and the
    // canvas released even on the hideOnFirstPage early return.
    pdfCanvas.openTag(CanvasArtifact())
    try {
        val canvas = Canvas(pdfCanvas, footerRect)
        // Safety net: render overflow rather than silently dropping content.
        canvas.setProperty(Property.OVERFLOW_Y, OverflowPropertyValue.VISIBLE)
        canvas.setProperty(Property.OVERFLOW_X, OverflowPropertyValue.VISIBLE)

        // Render the footer node's slots with page-scoped system parameters
        if (footerNode != null) {
            val pageNumber = pdfDoc.getPageNumber(page)
            val hideOnFirstPage = footerNode.props?.get("hideOnFirstPage") == true
            if (hideOnFirstPage && pageNumber == 1) return
            val totalPages = context.totalPages ?: pdfDoc.numberOfPages
            val wrapper = buildBandWrapper(
                node = footerNode,
                document = document,
                baseContext = context,
                registry = registry,
                consumedMarginKeys = FOOTER_CONSUMED_MARGINS,
                componentDefaultsKey = FOOTER_COMPONENT_KEY,
                pageNumber = pageNumber,
                totalPages = totalPages,
            )
            canvas.add(wrapper)
        }

        canvas.close()
    } finally {
        pdfCanvas.closeTag()
        pdfCanvas.release()
    }
}

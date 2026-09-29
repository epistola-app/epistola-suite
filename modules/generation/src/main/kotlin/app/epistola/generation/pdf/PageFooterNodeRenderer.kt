// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.layout.element.IElement

/**
 * Renderer for "pagefooter" nodes. The band itself is painted at the end of each page. In the body
 * flow of the section-band path this emits a zero-height [PageBandAnchor] that registers the
 * occurrence, so its position decides which pages it applies to; otherwise it emits nothing.
 */
class PageFooterNodeRenderer : NodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: RenderContext,
        registry: NodeRendererRegistry,
    ): List<IElement> {
        val bands = context.pageBands ?: return emptyList()
        return listOf(PageBandAnchor(bands.register(PageBandKind.FOOTER, node.id, context)))
    }
}

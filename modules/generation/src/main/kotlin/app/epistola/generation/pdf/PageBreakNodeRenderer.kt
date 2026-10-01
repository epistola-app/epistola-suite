// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.IElement

/**
 * Renders a "pagebreak" node to force content onto a new page. In the body flow of the
 * section-band path it also starts a new page section (see [PageBandCollector]).
 */
class PageBreakNodeRenderer : NodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: RenderContext,
        registry: NodeRendererRegistry,
    ): List<IElement> {
        val bands = context.pageBands ?: return listOf(AreaBreak())
        return listOf(SectionPageBreak(bands.startSection(), bands))
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.layout.element.IElement

/**
 * Renders a `placeholder` node.
 *
 * A placeholder owns two slots:
 *   - `default` — the stencil author's content; shown when no override exists.
 *   - `fill`    — the embedding template's override; takes precedence when non-empty.
 *
 * The renderer dispatches by slot name (not position) and falls back to default
 * when the fill slot is empty.
 *
 * Expressions are scoped lexically: the fill is the embedding document's content and reads
 * [RenderContext.fillScope] — what was in scope where the stencil instance sits — while the default
 * reads the stencil's own scope, `params` included.
 */
class PlaceholderNodeRenderer : NodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: RenderContext,
        registry: NodeRendererRegistry,
    ): List<IElement> {
        val fillSlotId = node.slots.firstOrNull {
            document.slots[it]?.name == PlaceholderNodeKeys.SLOT_FILL
        }
        val defaultSlotId = node.slots.firstOrNull {
            document.slots[it]?.name == PlaceholderNodeKeys.SLOT_DEFAULT
        }
        val fillSlot = fillSlotId?.let { document.slots[it] }

        if (fillSlotId != null && fillSlot != null && fillSlot.children.isNotEmpty()) {
            // The fill reads the scope it was written in (outside the stencil); the default is the
            // stencil's own content and reads the stencil's scope.
            val fillContext = context.fillScope?.let(context::withExpressionScope) ?: context
            return registry.renderSlot(fillSlotId, document, fillContext)
        }
        return defaultSlotId?.let { registry.renderSlot(it, document, context) } ?: emptyList()
    }
}

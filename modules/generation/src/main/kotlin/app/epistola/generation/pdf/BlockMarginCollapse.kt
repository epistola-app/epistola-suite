// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import com.itextpdf.layout.borders.Border
import com.itextpdf.layout.element.AbstractElement
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.properties.Property
import com.itextpdf.layout.properties.UnitValue

/**
 * Collapses a container's vertical margins with its children's, as CSS does for a block parent and
 * its first or last in-flow child (rendering defaults V8, [RenderingDefaults.collapseBlockMargins]).
 *
 * - **Bottom:** the container's bottom margin and its last child's become one, the larger of the
 *   two, kept on the container; the child's goes to zero.
 * - **Top:** likewise for the top margin and the first child's.
 * - **Not collapsed** when something separates the two margins, as in CSS: padding or a border on
 *   that side of the container, or, for the bottom, a fixed or minimum height. A child that is not
 *   an element with its own margins (a page break) is left alone. Table cells and columns are their
 *   own boxes: their content never collapses through them, because only containers call this.
 * - **Siblings** keep their own margins; only parent and child meet.
 *
 * Children are rendered before their container, so an inner container has already taken its own
 * last child's margin, and the collapse carries through any depth of nesting.
 */
internal object BlockMarginCollapse {

    fun collapse(container: Div) {
        val children = container.children
        (children.lastOrNull() as? AbstractElement<*>)?.let { last ->
            if (!separated(container, Property.PADDING_BOTTOM, Property.BORDER_BOTTOM) && !hasHeight(container)) {
                merge(container, last, Property.MARGIN_BOTTOM) { e, v -> e.setProperty(Property.MARGIN_BOTTOM, UnitValue.createPointValue(v)) }
            }
        }
        (children.firstOrNull() as? AbstractElement<*>)?.let { first ->
            if (!separated(container, Property.PADDING_TOP, Property.BORDER_TOP)) {
                merge(container, first, Property.MARGIN_TOP) { e, v -> e.setProperty(Property.MARGIN_TOP, UnitValue.createPointValue(v)) }
            }
        }
    }

    private fun merge(container: Div, child: AbstractElement<*>, side: Int, set: (AbstractElement<*>, Float) -> Unit) {
        val childMargin = points(child, side) ?: return
        if (childMargin <= 0f) return
        val own = points(container, side) ?: return
        set(container, maxOf(own, childMargin))
        set(child, 0f)
    }

    /** The element's own margin on [side] in points; 0 when unset, null when not in points (left alone). */
    private fun points(element: AbstractElement<*>, side: Int): Float? {
        val value = element.getOwnProperty<UnitValue>(side) ?: return 0f
        return if (value.isPointValue) value.value else null
    }

    private fun separated(container: Div, padding: Int, border: Int): Boolean {
        val pad = container.getOwnProperty<UnitValue>(padding)
        if (pad != null && pad.value > 0f) return true
        val sideBorder = container.getOwnProperty<Border>(border)
        return sideBorder != null && sideBorder.width > 0f
    }

    private fun hasHeight(container: Div): Boolean = container.getOwnProperty<UnitValue>(Property.HEIGHT) != null ||
        container.getOwnProperty<UnitValue>(Property.MIN_HEIGHT) != null
}

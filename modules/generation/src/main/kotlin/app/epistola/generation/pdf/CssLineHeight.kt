// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.layout.LayoutContext
import com.itextpdf.layout.layout.LayoutResult
import com.itextpdf.layout.properties.Leading
import com.itextpdf.layout.properties.Property
import com.itextpdf.layout.renderer.IRenderer
import com.itextpdf.layout.renderer.ParagraphRenderer

/**
 * A CSS-like `lineHeight` for a paragraph: lines sit `fontSize × lineHeight` apart, so `1.0` is
 * solid and `1.4` is 1.4× the font size whatever the font, as in the editor.
 *
 * iText's multiplied leading scales the font's own line height (ascent minus descent) instead,
 * which differs per font. The font size is only known once the paragraph is laid out, because it
 * is inherited from the block around it, so the leading is fixed at layout time by [Renderer].
 * Used from rendering defaults V6 ([RenderingDefaults.cssLineHeight]).
 */
internal object CssLineHeight {

    fun apply(paragraph: Paragraph, lineHeight: Float) {
        paragraph.setNextRenderer(Renderer(paragraph, lineHeight))
    }

    private class Renderer(paragraph: Paragraph, private val lineHeight: Float) : ParagraphRenderer(paragraph) {

        override fun layout(layoutContext: LayoutContext): LayoutResult {
            getPropertyAsUnitValue(Property.FONT_SIZE)?.takeIf { it.isPointValue }?.let { fontSize ->
                setProperty(Property.LEADING, Leading(Leading.FIXED, fontSize.value * lineHeight))
            }
            return super.layout(layoutContext)
        }

        override fun getNextRenderer(): IRenderer = Renderer(modelElement as Paragraph, lineHeight)
    }
}

package app.epistola.generation.pdf

import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.layout.borders.DashedBorder
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.IElement
import com.itextpdf.layout.element.Paragraph

/**
 * Renders a visible error placeholder for use in [RenderMode.PREVIEW].
 * Shows a light grey box with a dashed red border and error message,
 * making it clear to the user that something failed without crashing the render.
 */
object ErrorPlaceholder {

    private val BACKGROUND_COLOR = DeviceRgb(245, 245, 245)
    private val BORDER = DashedBorder(ColorConstants.RED, 1f)
    private const val FONT_SIZE = 8f
    private const val PADDING = 6f

    fun render(message: String): List<IElement> {
        val div = Div()
            .setBackgroundColor(BACKGROUND_COLOR)
            .setBorder(BORDER)
            .setPadding(PADDING)
        val paragraph = Paragraph(message)
            .setFontSize(FONT_SIZE)
            .setFontColor(ColorConstants.RED)
        div.add(paragraph)
        return listOf(div)
    }
}

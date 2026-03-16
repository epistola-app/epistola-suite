package app.epistola.generation.pdf

import app.epistola.template.model.Margins
import app.epistola.template.model.Orientation
import app.epistola.template.model.PageFormat
import app.epistola.template.model.PageSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PageSettingsDefaultsTest {

    @Test
    fun `PageSettings uses A4 portrait defaults when constructed with only margins`() {
        val settings = PageSettings(margins = Margins(top = 10, right = 10, bottom = 10, left = 10))

        assertEquals(PageFormat.A4, settings.format)
        assertEquals(Orientation.portrait, settings.orientation)
        assertEquals(10, settings.margins.top)
        assertNull(settings.backgroundColor)
    }

    @Test
    fun `PageSettings uses all defaults when constructed with no arguments`() {
        val settings = PageSettings()

        assertEquals(PageFormat.A4, settings.format)
        assertEquals(Orientation.portrait, settings.orientation)
        assertEquals(20, settings.margins.top)
        assertEquals(20, settings.margins.right)
        assertEquals(20, settings.margins.bottom)
        assertEquals(20, settings.margins.left)
        assertNull(settings.backgroundColor)
    }

    @Test
    fun `PageSettings allows overriding format and orientation`() {
        val settings = PageSettings(
            format = PageFormat.Letter,
            orientation = Orientation.landscape,
        )

        assertEquals(PageFormat.Letter, settings.format)
        assertEquals(Orientation.landscape, settings.orientation)
    }
}

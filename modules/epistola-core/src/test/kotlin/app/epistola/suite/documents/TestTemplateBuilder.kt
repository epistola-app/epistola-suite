package app.epistola.suite.documents

import app.epistola.suite.templates.model.Margins
import app.epistola.suite.templates.model.Orientation
import app.epistola.suite.templates.model.PageFormat
import app.epistola.suite.templates.model.PageSettings
import app.epistola.suite.templates.model.TemplateModel
import java.util.UUID

/**
 * Helper to build minimal TemplateModel instances for testing.
 * Creates templates with empty blocks - suitable for tests that only need
 * valid template structure without actual content.
 *
 * Note: Generated PDFs from these templates will be valid but contain no visible content.
 * If your test needs actual content, create TextBlock instances with TipTap JSON content.
 */
object TestTemplateBuilder {
    fun buildMinimal(
        id: String = UUID.randomUUID().toString(),
        name: String = "Test Template",
    ): TemplateModel = TemplateModel(
        id = id,
        name = name,
        version = 1,
        pageSettings = PageSettings(
            format = PageFormat.A4,
            orientation = Orientation.portrait,
            margins = Margins(top = 20, right = 20, bottom = 20, left = 20),
        ),
        blocks = emptyList(),
        documentStyles = emptyMap(),
    )
}

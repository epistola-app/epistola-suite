package app.epistola.suite.demo

import app.epistola.suite.templates.model.DataExample
import app.epistola.template.model.TemplateModel
import tools.jackson.databind.node.ObjectNode

/**
 * Complete template definition for demo loading.
 * Includes metadata (dataModel, dataExamples) and visual content (templateModel).
 *
 * This format is used to load realistic template examples from JSON files,
 * making it easy to version control and maintain demo content without code changes.
 */
data class TemplateDefinition(
    /** Slug identifier for the template (3-50 chars, lowercase, hyphens allowed) */
    val slug: String,

    /** Display name of the template */
    val name: String,

    /** JSON Schema for validating input data (null if no validation required) */
    val dataModel: ObjectNode?,

    /** Example data sets for previewing the template */
    val dataExamples: List<DataExample>,

    /** Visual layout including blocks, styles, and page settings */
    val templateModel: TemplateModel,
)

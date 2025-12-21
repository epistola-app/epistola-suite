package app.epistola.suite.templates.model

import tools.jackson.databind.node.ObjectNode

/**
 * A named example data set that can be validated against the template's dataModel.
 * Used for previewing templates with sample data during editing.
 */
data class DataExample(
    val name: String,
    val data: ObjectNode,
)

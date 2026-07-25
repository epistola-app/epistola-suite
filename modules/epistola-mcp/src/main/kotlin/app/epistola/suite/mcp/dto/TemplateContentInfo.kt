// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.queries.EditorContext

/**
 * Full content needed to design or inspect a template variant.
 *
 * `templateModel` is the structured node/slot graph (the editable document
 * representation). `dataModel` is the JSON Schema describing what input data
 * the template expects, and `dataExamples` are named sample datasets that
 * can be used to preview rendering.
 */
data class TemplateContentInfo(
    val templateName: String,
    val variantAttributes: Map<String, String>,
    /** TemplateDocument node/slot graph. AI tools edit this in subsequent draft updates (write tools — out of MVP). */
    val templateModel: Any,
    val dataExamples: List<DataExampleInfo>,
    /** JSON Schema describing the template's input data. May be null for templates without a contract yet. */
    val dataModel: Any?,
) {
    companion object {
        fun from(context: EditorContext): TemplateContentInfo = TemplateContentInfo(
            templateName = context.templateName,
            variantAttributes = context.variantAttributes,
            templateModel = context.templateModel,
            dataExamples = context.dataExamples.map { DataExampleInfo.from(it) },
            dataModel = context.dataModel,
        )
    }
}

data class DataExampleInfo(
    val id: String,
    val name: String,
    /** Raw JSON sample data. */
    val data: Any,
) {
    companion object {
        fun from(example: DataExample): DataExampleInfo = DataExampleInfo(
            id = example.id,
            name = example.name,
            data = example.data,
        )
    }
}

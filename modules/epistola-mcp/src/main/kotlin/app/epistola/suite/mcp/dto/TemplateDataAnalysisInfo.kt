// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.templates.validation.TemplateDataAnalysis

/**
 * How preview data measures up against a template's data contract: what is missing, what is wrong,
 * and a JSON Schema describing only the missing part. All paths are JSON Pointers into the data
 * (`/customer/address/city`).
 */
data class TemplateDataAnalysisInfo(
    /** True when the data satisfies the contract. Absent optional fields do not make it invalid. */
    val valid: Boolean,
    /**
     * Absent fields, outermost node first (a missing object is one entry), in schema declaration
     * order. Required ones are always listed; optional ones only when the template uses them.
     */
    val missingFields: List<MissingFieldInfo>,
    /** Supplied values that break the contract. */
    val invalidFields: List<InvalidFieldInfo>,
    /**
     * The contract cut down to the missing fields; null when nothing is missing. Fields inside
     * array items are not in it, because a schema cannot address one array element.
     */
    val missingDataSchema: Any?,
) {
    data class MissingFieldInfo(
        val path: String,
        val required: Boolean,
        /** The field's schema, local `$ref`s inlined. */
        val schema: Any,
    )

    data class InvalidFieldInfo(
        val path: String,
        /** The JSON Schema keyword that failed (`type`, `enum`, `minimum`, …). */
        val keyword: String,
        val message: String,
        val schema: Any?,
    )

    companion object {
        fun from(analysis: TemplateDataAnalysis): TemplateDataAnalysisInfo = TemplateDataAnalysisInfo(
            valid = analysis.valid,
            missingFields = analysis.missingFields.map { MissingFieldInfo(it.path, it.required, it.schema) },
            invalidFields = analysis.invalidFields.map { InvalidFieldInfo(it.path, it.keyword, it.message, it.schema) },
            missingDataSchema = analysis.missingDataSchema,
        )
    }
}

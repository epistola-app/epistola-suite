// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.validation

import tools.jackson.databind.node.ObjectNode

/**
 * Template data does not satisfy the template's data contract. Carries the full [analysis], so a
 * caller learns which fields to supply or correct instead of parsing [message], and the [data] it
 * was run on (after schema defaults), so a caller can echo a rejected value.
 *
 * Extends [IllegalArgumentException] because that is what preview threw before this existed;
 * callers catching it keep working.
 */
class TemplateDataInvalidException(
    val analysis: TemplateDataAnalysis,
    val data: ObjectNode,
) : IllegalArgumentException(describe(analysis)) {
    private companion object {
        fun describe(analysis: TemplateDataAnalysis): String {
            val problems = analysis.missingFields.filter { it.required }.map { "${it.path}: is required" } +
                analysis.invalidFields.map { "${it.path.ifEmpty { "/" }}: ${it.message}" }
            return "Data validation failed: ${problems.joinToString("; ")}"
        }
    }
}

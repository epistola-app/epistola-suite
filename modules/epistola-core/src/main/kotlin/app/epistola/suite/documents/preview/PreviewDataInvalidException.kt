// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.preview

import tools.jackson.databind.node.ObjectNode

/**
 * Preview data does not satisfy the template's data contract. Carries the full [analysis], so a
 * caller learns which fields to supply or correct instead of parsing [message], and the [data] it
 * was run on (after schema defaults), so a caller can echo a rejected value.
 *
 * Extends [IllegalArgumentException] because that is what preview threw before; callers catching
 * it keep working.
 */
class PreviewDataInvalidException(
    val analysis: PreviewDataAnalysis,
    val data: ObjectNode,
) : IllegalArgumentException(describe(analysis)) {
    private companion object {
        fun describe(analysis: PreviewDataAnalysis): String {
            val problems = analysis.missingFields.filter { it.required }.map { "${it.path}: is required" } +
                analysis.invalidFields.map { "${it.path.ifEmpty { "/" }}: ${it.message}" }
            return "Data validation failed: ${problems.joinToString("; ")}"
        }
    }
}

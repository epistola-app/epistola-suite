// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.model

import tools.jackson.databind.node.ObjectNode

/**
 * A template's data contract as it is now: the JSON Schema its data must satisfy, null when it has
 * none, and the examples it is previewed and checked with. Versioned with the catalog, not apart.
 */
data class DataContract(
    val dataModel: ObjectNode?,
    val dataExamples: List<DataExample>,
    /** The template's revision as read with the contract; see [app.epistola.suite.common.Revisions]. */
    val revision: Long = 1,
)

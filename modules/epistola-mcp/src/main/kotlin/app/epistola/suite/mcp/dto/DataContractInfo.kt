// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.templates.model.DataContract

/**
 * A template's data contract as it is now — JSON Schema describing what input data the template
 * expects, plus named example datasets. Versioned with the catalog, not apart.
 */
data class DataContractInfo(
    val templateId: String,
    val catalogId: String,
    /**
     * The JSON Schema for the input data: this is what every validation path (generation, preview,
     * the REST validate endpoint) checks data against, and what the data-contract editor authors.
     * May be null, which means no validation. Extra properties not declared here are accepted and
     * ignored.
     */
    val dataModel: Any?,
    val dataExamples: List<DataExampleInfo>,
    /** Pass back as `expectedRevision` when updating: the update is refused if someone changed it since. */
    val revision: Long? = null,
) {
    companion object {
        fun from(templateId: String, catalogId: String, contract: DataContract): DataContractInfo = DataContractInfo(
            templateId = templateId,
            catalogId = catalogId,
            dataModel = contract.dataModel,
            dataExamples = contract.dataExamples.map { DataExampleInfo.from(it) },
            revision = contract.revision,
        )
    }
}

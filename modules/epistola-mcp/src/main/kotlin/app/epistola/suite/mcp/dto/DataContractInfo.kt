// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.templates.contracts.model.ContractVersion
import java.time.OffsetDateTime

/**
 * A data contract version — JSON Schema describing what input data the
 * template expects, plus named example datasets. Contract versions are
 * versioned independently of template versions; templates link to a
 * contract version when they're published.
 */
data class DataContractInfo(
    /** Sequential contract version number for this template. */
    val versionId: Int,
    val templateId: String,
    val catalogId: String,
    /** "draft" or "published". */
    val status: String,
    /**
     * Vestigial second schema. Despite the name it is **not** what submitted data is validated
     * against, and it is not kept in step with [dataModel]. Prefer [dataModel]. May be null.
     */
    val schema: Any?,
    /**
     * The authoritative JSON Schema for the input data: this is what every validation path
     * (generation, preview, the REST validate endpoint) actually checks data against, and what
     * the data-contract editor authors. May be null, which means no validation. Note that extra
     * properties not declared here are currently accepted and ignored.
     */
    val dataModel: Any?,
    val dataExamples: List<DataExampleInfo>,
    val createdAt: OffsetDateTime,
    val publishedAt: OffsetDateTime?,
) {
    companion object {
        fun from(contract: ContractVersion): DataContractInfo = DataContractInfo(
            versionId = contract.id.value,
            templateId = contract.templateKey.value,
            catalogId = contract.catalogKey.value,
            status = contract.status.name.lowercase(),
            schema = contract.schema,
            dataModel = contract.dataModel,
            dataExamples = contract.dataExamples.map { DataExampleInfo.from(it) },
            createdAt = contract.createdAt,
            publishedAt = contract.publishedAt,
        )
    }
}

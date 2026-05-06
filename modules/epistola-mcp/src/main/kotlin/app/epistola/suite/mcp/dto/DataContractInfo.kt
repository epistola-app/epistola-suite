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
    /** Full JSON Schema for the input data. May be null for legacy contracts. */
    val schema: Any?,
    /** UI-rendering schema (similar to JSON Schema with form-display hints). May be null. */
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

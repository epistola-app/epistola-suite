package app.epistola.suite.templates.model

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VersionKey
import org.jdbi.v3.json.Json
import tools.jackson.databind.node.ObjectNode
import java.time.OffsetDateTime

enum class ContractVersionStatus {
    DRAFT,
    PUBLISHED,
}

data class ContractVersion(
    val id: VersionKey,
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val templateKey: TemplateKey,
    @Json val schema: ObjectNode?,
    @Json val dataModel: ObjectNode?,
    @Json val dataExamples: DataExamples,
    val status: ContractVersionStatus,
    val createdAt: OffsetDateTime,
    val publishedAt: OffsetDateTime?,
)

data class ContractVersionSummary(
    val id: VersionKey,
    val status: ContractVersionStatus,
    val createdAt: OffsetDateTime,
    val publishedAt: OffsetDateTime?,
)

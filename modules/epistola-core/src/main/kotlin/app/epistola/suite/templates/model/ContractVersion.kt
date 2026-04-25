package app.epistola.suite.templates.model

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VersionKey
import org.jdbi.v3.json.Json
import tools.jackson.databind.node.ObjectNode
import java.time.OffsetDateTime
import java.util.UUID

enum class ContractVersionStatus {
    DRAFT,
    PUBLISHED,
}

/**
 * Versioned data contract for a template.
 *
 * @property schema JSON Schema (2020-12) for strict validation. May be null if no validation is needed.
 * @property dataModel JSON Schema describing the input structure, used by the visual editor. This is
 *           the primary schema used for validation throughout the application.
 * @property dataExamples Named sample data sets conforming to the schema, used for preview and testing.
 */
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
    val createdBy: UUID? = null,
)

data class ContractVersionSummary(
    val id: VersionKey,
    val status: ContractVersionStatus,
    val createdAt: OffsetDateTime,
    val publishedAt: OffsetDateTime?,
)

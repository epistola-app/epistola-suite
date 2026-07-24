// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.contracts.model

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.templates.model.DataExamples
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
 * @property dataModel JSON Schema describing the expected input structure, authored by the visual
 *           editor. **This is the schema every validation path actually uses** — generation, both
 *           previews, and the REST validate endpoint all read `dataModel`. Null means no validation.
 * @property schema Written by `CreateContractVersion` / `UpdateContractVersion` and surfaced over MCP,
 *           but **no validation path consults it** — despite the name, it is not the schema data is
 *           checked against. Vestigial, pending a decision to use or drop it.
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

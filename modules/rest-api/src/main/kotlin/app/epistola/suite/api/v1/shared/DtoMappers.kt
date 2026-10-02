// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.api.model.AttributeDto
import app.epistola.api.model.AttributeDtoCodeListBinding
import app.epistola.api.model.DataExampleDto
import app.epistola.api.model.EnvironmentDto
import app.epistola.api.model.ResourceStatus
import app.epistola.api.model.TemplateDto
import app.epistola.api.model.TemplateSummaryDto
import app.epistola.api.model.TenantDto
import app.epistola.api.model.VariantDto
import app.epistola.api.model.VariantSummaryDto
import app.epistola.suite.attributes.model.VariantAttributeDefinition
import app.epistola.suite.environments.Environment
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.contracts.model.ContractVersion
import app.epistola.suite.templates.model.TemplateVariant
import app.epistola.suite.templates.model.VariantSummary
import app.epistola.suite.tenants.Tenant
import tools.jackson.databind.ObjectMapper

internal fun Tenant.toDto() = TenantDto(
    slug = id.value,
    name = name,
    createdAt = createdAt,
)

internal fun VariantAttributeDefinition.toDto() = AttributeDto(
    slug = id.value,
    tenantId = tenantKey.value,
    catalog = catalogKey.value,
    displayName = displayName,
    allowedValues = allowedValues,
    catalogType = when (catalogType) {
        app.epistola.suite.catalog.CatalogType.AUTHORED -> AttributeDto.CatalogType.AUTHORED
        app.epistola.suite.catalog.CatalogType.SUBSCRIBED -> AttributeDto.CatalogType.SUBSCRIBED
    },
    readOnly = catalogType == app.epistola.suite.catalog.CatalogType.SUBSCRIBED,
    createdAt = createdAt,
    lastModified = updatedAt,
    description = null,
    codeListBinding = codeListId?.let { id ->
        AttributeDtoCodeListBinding(catalog = id.catalogKey.value, slug = id.key.value)
    },
)

internal fun Environment.toDto() = EnvironmentDto(
    slug = id.value,
    tenantId = tenantKey.value,
    name = name,
    createdAt = createdAt,
)

internal fun DocumentTemplate.toSummaryDto() = TemplateSummaryDto(
    slug = id.value,
    tenantId = tenantKey.value,
    name = name,
    createdAt = createdAt,
    lastModified = updatedAt,
)

internal fun DocumentTemplate.toDto(
    objectMapper: ObjectMapper,
    variantSummaries: List<VariantSummary>,
    contractVersion: ContractVersion? = null,
    status: WorkingCopyStatus,
) = TemplateDto(
    slug = id.value,
    tenantId = tenantKey.value,
    name = name,
    schema = contractVersion?.schema?.let { objectMapper.valueToTree(it) },
    dataModel = contractVersion?.dataModel?.let { objectMapper.valueToTree(it) },
    dataExamples = contractVersion?.dataExamples?.map { example ->
        DataExampleDto(
            id = example.id,
            name = example.name,
            data = objectMapper.valueToTree(example.data),
        )
    },
    variants = variantSummaries.map { it.toDto(status.of("template", id.value, it.hasDraft, it.publishedVersions.isNotEmpty())) },
    createdAt = createdAt,
    lastModified = updatedAt,
)

internal fun VariantSummary.toDto(status: ResourceStatus) = VariantSummaryDto(
    slug = id.value,
    title = title,
    attributes = attributes,
    isDefault = isDefault,
    status = status,
)

internal fun TemplateVariant.toDto(status: ResourceStatus) = VariantDto(
    slug = id.value,
    templateId = templateKey.value,
    title = title,
    description = description,
    attributes = attributes,
    isDefault = isDefault,
    status = status,
    createdAt = createdAt,
    lastModified = updatedAt,
)

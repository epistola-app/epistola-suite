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
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.environments.Environment
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.model.DataContract
import app.epistola.suite.templates.model.TemplateVariant
import app.epistola.suite.templates.model.VariantSummary
import app.epistola.suite.tenants.Tenant
import tools.jackson.databind.ObjectMapper

internal fun Tenant.toDto() = TenantDto(
    key = id.value,
    name = name,
    createdAt = createdAt,
)

internal fun CatalogType.toAttributeCatalogType() = when (this) {
    CatalogType.AUTHORED -> app.epistola.api.model.CatalogType.AUTHORED
    CatalogType.SUBSCRIBED -> app.epistola.api.model.CatalogType.SUBSCRIBED
}

/**
 * An attribute of the working copy. [bindingCatalog] is the catalog of its code list binding, which
 * the binding names in full.
 */
internal fun VariantAttributeDefinition.toDto(catalog: Catalog, bindingCatalog: Catalog?) = AttributeDto(
    key = id.value,
    tenantKey = tenantKey.value,
    registry = catalog.registry,
    namespace = catalog.namespace,
    catalogKey = catalog.id.value,
    displayName = displayName,
    allowedValues = allowedValues,
    catalogType = catalogType.toAttributeCatalogType(),
    readOnly = catalogType == CatalogType.SUBSCRIBED,
    createdAt = createdAt,
    lastModified = updatedAt,
    // Attributes keep no description.
    description = null,
    codeListBinding = codeListId?.let { id ->
        AttributeDtoCodeListBinding(
            registry = bindingCatalog?.registry ?: catalog.registry,
            namespace = bindingCatalog?.namespace ?: catalog.namespace,
            catalogKey = id.catalogKey.value,
            key = id.key.value,
        )
    },
)

internal fun Environment.toDto() = EnvironmentDto(
    key = id.value,
    tenantKey = tenantKey.value,
    name = name,
    production = production,
    createdAt = createdAt,
)

internal fun DocumentTemplate.toSummaryDto() = TemplateSummaryDto(
    key = id.value,
    tenantKey = tenantKey.value,
    name = name,
    createdAt = createdAt,
    lastModified = updatedAt,
    // Templates keep no description.
    description = null,
)

/** A template of the working copy, with its variants' statuses and its data contract. */
internal fun DocumentTemplate.toDto(
    objectMapper: ObjectMapper,
    variantSummaries: List<VariantSummary>,
    contract: DataContract?,
    statuses: ResourceStatuses,
) = TemplateDto(
    key = id.value,
    tenantKey = tenantKey.value,
    name = name,
    description = null,
    // The contract's `schema` (input parameters) has no counterpart in the suite: the data model is the schema.
    schema = null,
    dataModel = contract?.dataModel,
    dataExamples = contract?.dataExamples?.map { example ->
        DataExampleDto(
            key = example.key ?: example.id,
            name = example.name,
            data = objectMapper.valueToTree(example.data),
        )
    },
    variants = variantSummaries.map { it.toDto(statuses.of("template", id.value, it.ready)) },
    createdAt = createdAt,
    lastModified = updatedAt,
)

internal fun VariantSummary.toDto(status: ResourceStatus) = VariantSummaryDto(
    key = id.value,
    title = title,
    attributes = attributes,
    isDefault = isDefault,
    status = status,
)

internal fun TemplateVariant.toDto(status: ResourceStatus) = VariantDto(
    key = id.value,
    templateKey = templateKey.value,
    title = title,
    description = description,
    attributes = attributes,
    isDefault = isDefault,
    status = status,
    createdAt = createdAt,
    lastModified = updatedAt,
)

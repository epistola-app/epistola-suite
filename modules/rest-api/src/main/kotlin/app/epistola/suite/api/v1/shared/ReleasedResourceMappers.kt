// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.api.model.CodeListEntryDto
import app.epistola.api.model.DataExampleDto
import app.epistola.api.model.FontDto
import app.epistola.api.model.FontVariantDto
import app.epistola.api.model.ImageDto
import app.epistola.api.model.ResourceStatus
import app.epistola.api.model.StencilContentDto
import app.epistola.api.model.StencilDto
import app.epistola.api.model.StencilSummaryDto
import app.epistola.api.model.TemplateDto
import app.epistola.api.model.TemplateSummaryDto
import app.epistola.api.model.ThemeDto
import app.epistola.api.model.VariantContentDto
import app.epistola.api.model.VariantDto
import app.epistola.api.model.VariantSummaryDto
import app.epistola.catalog.protocol.CatalogKeys
import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.ImageResource
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.catalog.protocol.ThemeResource
import app.epistola.catalog.protocol.VariantEntry
import app.epistola.suite.catalog.releases.ResolvedCatalogRelease
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/*
 * Resources of a released catalog version, as the release holds them (wire form), mapped to the
 * contract's DTOs. Everything a release holds is `released`, and its timestamps are the release's
 * (see [releasedAt]): a release records none per resource.
 */

internal fun TemplateResource.toSummaryDto(at: ResolvedCatalogRelease) = TemplateSummaryDto(
    key = key,
    tenantKey = at.tenantKey.value,
    name = name,
    createdAt = at.releasedAt,
    lastModified = at.releasedAt,
    description = description,
)

internal fun TemplateResource.toDto(at: ResolvedCatalogRelease, objectMapper: ObjectMapper): TemplateDto {
    val taken = mutableSetOf<String>()
    return TemplateDto(
        key = key,
        tenantKey = at.tenantKey.value,
        name = name,
        description = description,
        schema = null,
        dataModel = dataModel?.let { objectMapper.valueToTree<ObjectNode>(it) },
        dataExamples = dataExamples?.map { example ->
            val exampleKey = example.key ?: CatalogKeys.deriveDataExampleKey(example.name, taken)
            taken += exampleKey
            DataExampleDto(key = exampleKey, name = example.name, data = objectMapper.valueToTree(example.data))
        },
        variants = variants.map {
            VariantSummaryDto(
                key = it.key,
                title = it.title ?: it.key,
                attributes = it.attributes.orEmpty(),
                isDefault = it.isDefault,
                status = ResourceStatus.RELEASED,
            )
        },
        createdAt = at.releasedAt,
        lastModified = at.releasedAt,
    )
}

internal fun VariantEntry.toDto(template: TemplateResource, at: ResolvedCatalogRelease) = VariantDto(
    key = key,
    templateKey = template.key,
    title = title ?: key,
    attributes = attributes.orEmpty(),
    isDefault = isDefault,
    status = ResourceStatus.RELEASED,
    createdAt = at.releasedAt,
    lastModified = at.releasedAt,
    description = null,
)

/** A variant without a model of its own renders the template's. */
internal fun VariantEntry.toContentDto(template: TemplateResource, at: ResolvedCatalogRelease) = VariantContentDto(
    variantKey = key,
    templateModel = templateModel ?: template.templateModel,
    status = ResourceStatus.RELEASED,
    lastModified = at.releasedAt,
    readyAt = null,
)

internal fun StencilResource.toDto(at: ResolvedCatalogRelease) = StencilDto(
    key = key,
    tenantKey = at.tenantKey.value,
    name = name,
    status = ResourceStatus.RELEASED,
    createdAt = at.releasedAt,
    lastModified = at.releasedAt,
    description = description,
    tags = tags.ifEmpty { null },
)

internal fun StencilResource.toSummaryDto(at: ResolvedCatalogRelease) = StencilSummaryDto(
    key = key,
    tenantKey = at.tenantKey.value,
    name = name,
    status = ResourceStatus.RELEASED,
    createdAt = at.releasedAt,
    lastModified = at.releasedAt,
    description = description,
    tags = tags.ifEmpty { null },
)

internal fun StencilResource.toContentDto(at: ResolvedCatalogRelease, objectMapper: ObjectMapper) = StencilContentDto(
    stencilKey = key,
    content = content,
    status = ResourceStatus.RELEASED,
    lastModified = at.releasedAt,
    parameterSchema = parameterSchema?.let { objectMapper.valueToTree<ObjectNode>(it) },
    readyAt = null,
)

/** A release holds a code list's entries as they were, whatever its source: it reads as inline. */
internal fun FontResource.toDto(at: ResolvedCatalogRelease) = FontDto(
    key = key,
    name = name,
    kind = FontDto.Kind.forValue(kind),
    registry = at.catalog.registry,
    namespace = at.catalog.namespace,
    catalogKey = at.catalogKey.value,
    readOnly = true,
    variants = variants.map { FontVariantDto(weight = it.weight, italic = it.italic) },
    createdAt = at.releasedAt,
    lastModified = at.releasedAt,
)

internal fun ImageResource.toDto(at: ResolvedCatalogRelease, sizeBytes: Long) = ImageDto(
    key = key,
    tenantKey = at.tenantKey.value,
    registry = at.catalog.registry,
    namespace = at.catalog.namespace,
    catalogKey = at.catalogKey.value,
    readOnly = true,
    name = name,
    mediaType = mediaType,
    sizeBytes = sizeBytes,
    createdAt = at.releasedAt,
    width = width,
    height = height,
)

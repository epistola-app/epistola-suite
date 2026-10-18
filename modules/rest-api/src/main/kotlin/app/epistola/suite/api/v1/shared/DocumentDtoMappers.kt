// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.api.model.DocumentDto
import app.epistola.api.model.DocumentGenerationItemDto
import app.epistola.api.model.DocumentGenerationJobDto
import app.epistola.api.model.GenerateBatchRequest
import app.epistola.api.model.GenerateDocumentRequest
import app.epistola.api.model.GenerationJobDetail
import app.epistola.api.model.GenerationJobResponse
import app.epistola.api.model.PreviewDocumentRequest
import app.epistola.api.model.VariantSelectionAttribute
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogName
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.DraftRef
import app.epistola.suite.common.ids.BatchKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.queries.DocumentMetadata
import app.epistola.suite.documents.queries.GenerationJobResult
import app.epistola.suite.documents.queries.PreviewDocument
import app.epistola.suite.templates.services.VariantSelectionCriteria
import app.epistola.suite.time.EpistolaClock
import app.epistola.suite.validation.ValidationException
import tools.jackson.databind.ObjectMapper

/*
 * Extension functions for mapping between domain models and API DTOs.
 */

// ==================== Document ====================

/** [catalog] names the document's catalog in full; null when it no longer exists. */
internal fun DocumentMetadata.toDto(catalog: CatalogName?) = DocumentDto(
    id = id.value,
    tenantKey = tenantId.value,
    registry = catalog?.registry,
    namespace = catalog?.namespace,
    catalogKey = catalogKey.value,
    templateKey = templateId.value,
    variantKey = variantId.value,
    releaseVersion = releaseVersion,
    draftId = draftId,
    draftLabel = draftLabel,
    filename = filename,
    correlationId = correlationId,
    contentType = contentType,
    sizeBytes = sizeBytes,
    createdAt = createdAt,
    createdBy = createdBy?.value?.toString(),
)

// ==================== Document Generation Request ====================

internal fun DocumentGenerationRequest.toJobDto() = DocumentGenerationJobDto(
    id = id.value,
    jobType = DocumentGenerationJobDto.JobType.SINGLE, // Always SINGLE in flattened structure
    status = DocumentGenerationJobDto.Status.valueOf(status.name),
    totalCount = 1, // Always 1 in flattened structure
    completedCount = if (status == app.epistola.suite.documents.model.RequestStatus.COMPLETED) 1 else 0,
    failedCount = if (status == app.epistola.suite.documents.model.RequestStatus.FAILED) 1 else 0,
    errorMessage = errorMessage,
    createdAt = createdAt,
    startedAt = startedAt,
    completedAt = completedAt,
    progressPercentage = if (isTerminal) 100.0 else 0.0,
)

internal fun DocumentGenerationRequest.toJobResponse() = GenerationJobResponse(
    requestId = id.value,
    status = GenerationJobResponse.Status.valueOf(status.name),
    jobType = GenerationJobResponse.JobType.SINGLE, // Always SINGLE in flattened structure
    totalCount = 1, // Always 1 in flattened structure
    createdAt = createdAt,
)

internal fun BatchKey.toJobResponse() = GenerationJobResponse(
    requestId = value, // Use batch ID as request ID for API compatibility
    status = GenerationJobResponse.Status.PENDING,
    jobType = GenerationJobResponse.JobType.BATCH,
    totalCount = 0, // Count not available without querying - caller should use batch endpoints
    createdAt = EpistolaClock.offsetDateTime(),
)

// ==================== Document Generation Item ====================
// NOTE: In the flattened structure, each request IS an item. Mapping a request to an ItemDto for API compatibility.

internal fun DocumentGenerationRequest.toItemDto(objectMapper: ObjectMapper, catalog: CatalogName?) = DocumentGenerationItemDto(
    id = id.value,
    registry = catalog?.registry,
    namespace = catalog?.namespace,
    catalogKey = catalogKey.value,
    templateKey = templateKey.value,
    variantKey = variantKey.value,
    releaseVersion = releaseVersion,
    draftId = draftId,
    draftLabel = draftLabel,
    environmentKey = environmentKey?.value,
    data = objectMapper.valueToTree(data),
    filename = filename,
    correlationId = correlationId,
    status = DocumentGenerationItemDto.Status.valueOf(status.name),
    errorMessage = errorMessage,
    documentId = documentKey?.value,
    createdAt = createdAt,
    startedAt = startedAt,
    completedAt = completedAt,
)

// ==================== Generation Job Result ====================

internal fun GenerationJobResult.toDto(objectMapper: ObjectMapper, catalogs: Map<CatalogKey, CatalogName>) = GenerationJobDetail(
    request = request.toJobDto(),
    items = items.map { it.toItemDto(objectMapper, catalogs[it.catalogKey]) },
)

// ==================== Request DTOs to Commands ====================

/**
 * A generation request names its template's catalog in full (registry, namespace, key); the suite
 * finds the catalog by that name. [catalogAt] resolves it, and 404s a name the tenant has no catalog by.
 */
internal fun GenerateDocumentRequest.toCommand(
    tenantKey: String,
    catalogAt: (registry: String?, namespace: String, catalogKey: String) -> Catalog,
): app.epistola.suite.documents.commands.GenerateDocument {
    require(variantKey == null || attributes == null) {
        "Cannot specify both variantKey and attributes"
    }
    val catalog = catalogAt(registry, namespace, catalogKey)
    return app.epistola.suite.documents.commands.GenerateDocument(
        tenantId = TenantKey.of(tenantKey),
        catalogKey = catalog.id,
        templateId = TemplateKey.of(templateKey),
        variantId = variantKey?.let { VariantKey.of(it) },
        variantSelectionCriteria = attributes?.toSelectionCriteria(catalog, catalogAt),
        releaseVersion = version,
        environmentId = environmentKey?.let { EnvironmentKey.of(it) },
        data = data,
        filename = filename,
        correlationId = correlationId,
        routingKey = routingKey,
    )
}

internal fun app.epistola.api.model.BatchGenerationItem.toBatchItem(
    catalogAt: (registry: String?, namespace: String, catalogKey: String) -> Catalog,
): app.epistola.suite.documents.commands.BatchGenerationItem {
    require(variantKey == null || attributes == null) {
        "Cannot specify both variantKey and attributes"
    }
    val catalog = catalogAt(registry, namespace, catalogKey)
    return app.epistola.suite.documents.commands.BatchGenerationItem(
        catalogKey = catalog.id,
        templateId = TemplateKey.of(templateKey),
        variantId = variantKey?.let { VariantKey.of(it) },
        variantSelectionCriteria = attributes?.toSelectionCriteria(catalog, catalogAt),
        releaseVersion = version,
        environmentId = environmentKey?.let { EnvironmentKey.of(it) },
        data = data,
        filename = filename,
        correlationId = correlationId,
        routingKey = routingKey,
    )
}

private fun List<VariantSelectionAttribute>.toSelectionCriteria(
    template: Catalog,
    catalogAt: (registry: String?, namespace: String, catalogKey: String) -> Catalog,
): VariantSelectionCriteria {
    val required = mutableMapOf<String, String>()
    val optional = mutableMapOf<String, String>()
    for (attr in this) {
        val storageKey = attr.toStorageKey(template, catalogAt)
        if (attr.required != false) {
            required[storageKey] = attr.value
        } else {
            optional[storageKey] = attr.value
        }
    }
    return VariantSelectionCriteria(
        requiredAttributes = required,
        optionalAttributes = optional,
    )
}

/**
 * Resolve the storage key for a `VariantSelectionAttribute`.
 *
 * The variant attribute map is stored as `Map<String, String>` keyed by
 * either a qualified `"<catalogKey>.<key>"` form or a bare key. We accept
 * three input shapes (in order of preference):
 *
 *  1. Explicit `catalogKey` — produces the qualified form. A `namespace` (and `registry`) names a
 *     catalog other than the template's, which must exist; omitted, they are the template's.
 *  2. Dotted `key` (`"<catalogKey>.<key>"`) — already qualified, used as-is.
 *  3. Bare key — kept as-is for the legacy tenant-wide lookup path.
 *
 * Catalog keys match `^[a-z][a-z0-9]*(-[a-z0-9]+)*$` (no `.`), so the
 * dotted-form split is unambiguous.
 */
private fun VariantSelectionAttribute.toStorageKey(
    template: Catalog,
    catalogAt: (registry: String?, namespace: String, catalogKey: String) -> Catalog,
): String {
    val qualifier = catalogKey ?: return key
    val catalog = if (namespace == null) {
        qualifier
    } else {
        catalogAt(registry ?: template.registry, namespace!!, qualifier).id.value
    }
    return "$catalog.$key"
}

internal fun GenerateBatchRequest.toCommand(
    tenantKey: String,
    catalogAt: (registry: String?, namespace: String, catalogKey: String) -> Catalog,
) = app.epistola.suite.documents.commands.GenerateDocumentBatch(
    tenantId = TenantKey.of(tenantKey),
    items = items.map { it.toBatchItem(catalogAt) },
    batchRoutingKey = routingKey,
)

// ==================== Preview ====================

/**
 * A preview renders a release (`version`, `environmentKey`) or, with `draft`, a draft: `next`, or
 * long-term work by its id. [requireDraft] resolves the draft and refuses one the catalog does not have.
 */
internal fun PreviewDocumentRequest.toQuery(
    tenantKey: String,
    catalogAt: (registry: String?, namespace: String, catalogKey: String) -> Catalog,
    requireDraft: (catalog: Catalog, version: String) -> Unit,
): PreviewDocument {
    if (draft != null && (version != null || environmentKey != null)) {
        throw ValidationException(field = "draft", message = "draft cannot be combined with version or environmentKey")
    }
    val catalog = catalogAt(registry, namespace, catalogKey)
    draft?.let { requireDraft(catalog, it) }
    return PreviewDocument(
        tenantId = TenantKey.of(tenantKey),
        catalogKey = catalog.id,
        templateId = TemplateKey.of(templateKey),
        variantId = variantKey?.let { VariantKey.of(it) },
        variantSelectionCriteria = attributes?.toSelectionCriteria(catalog, catalogAt),
        data = data,
        releaseVersion = version,
        workingCopy = draft != null,
        environmentId = environmentKey?.let { EnvironmentKey.of(it) },
        // The draft it names: the next version, or long-term work by its id.
        catalogVersion = draft?.let { CatalogVersionRef(catalog.tenantKey, catalog.id, requireNotNull(DraftRef.parse(it)) { "Not a draft: '$it'" }) },
    )
}

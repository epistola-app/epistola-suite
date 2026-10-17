// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.TemplatesApi
import app.epistola.api.VariantsApi
import app.epistola.api.model.CreateTemplateRequest
import app.epistola.api.model.CreateVariantRequest
import app.epistola.api.model.InvalidDataField
import app.epistola.api.model.MissingDataField
import app.epistola.api.model.TemplateDataValidationError
import app.epistola.api.model.TemplateDataValidationResult
import app.epistola.api.model.TemplateDto
import app.epistola.api.model.TemplateListResponse
import app.epistola.api.model.UpdateTemplateRequest
import app.epistola.api.model.UpdateVariantContentRequest
import app.epistola.api.model.UpdateVariantRequest
import app.epistola.api.model.ValidateTemplateDataRequest
import app.epistola.api.model.VariantContentDto
import app.epistola.api.model.VariantDto
import app.epistola.api.model.VariantListResponse
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.suite.api.v1.shared.ListSorting
import app.epistola.suite.api.v1.shared.Pagination
import app.epistola.suite.api.v1.shared.ResourceStatuses
import app.epistola.suite.api.v1.shared.SortDirection
import app.epistola.suite.api.v1.shared.UnsupportedSortException
import app.epistola.suite.api.v1.shared.catalogAt
import app.epistola.suite.api.v1.shared.catalogId
import app.epistola.suite.api.v1.shared.catalogVersionAt
import app.epistola.suite.api.v1.shared.draftAt
import app.epistola.suite.api.v1.shared.releasedResource
import app.epistola.suite.api.v1.shared.releasedResources
import app.epistola.suite.api.v1.shared.toContentDto
import app.epistola.suite.api.v1.shared.toDto
import app.epistola.suite.api.v1.shared.toSummaryDto
import app.epistola.suite.catalog.versions.ResolvedCatalogVersion
import app.epistola.suite.common.UUIDv7
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.documents.TemplateVariantNotFoundException
import app.epistola.suite.documents.queries.AnalyzeTemplateData
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.TemplateNotFoundException
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.DeleteDocumentTemplate
import app.epistola.suite.templates.commands.MarkTemplateReady
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.DeleteVariant
import app.epistola.suite.templates.commands.variants.MarkVariantReady
import app.epistola.suite.templates.commands.variants.SetDefaultVariant
import app.epistola.suite.templates.commands.variants.UpdateVariant
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.contracts.commands.UpdateDataContract
import app.epistola.suite.templates.contracts.queries.GetDataContract
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.model.TemplateVariant
import app.epistola.suite.templates.queries.CountDocumentTemplates
import app.epistola.suite.templates.queries.DocumentTemplateSort
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.templates.queries.variants.GetVariant
import app.epistola.suite.templates.queries.variants.GetVariantContent
import app.epistola.suite.templates.queries.variants.GetVariantSummaries
import app.epistola.suite.templates.queries.variants.ListVariants
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

/**
 * Templates and their variants in a catalog version. A template has one working model per variant
 * and a data contract, all in the draft; a variant's status is against the version's base and its
 * ready mark (contract ADR 0002). A released version reads the templates the release holds.
 */
@RestController
@RequestMapping("/api")
class EpistolaTemplateApi(
    private val objectMapper: ObjectMapper,
) : TemplatesApi,
    VariantsApi {

    // ================== Template operations ==================

    override fun listTemplates(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        q: String?,
        page: Int,
        size: Int,
        sort: String?,
        direction: String,
    ): ResponseEntity<TemplateListResponse> {
        // The contract does no validation on sort/direction (free-form strings), so we do it here.
        // An absent sort selects the default order; a non-null value must name a whitelisted field
        // (the whitelist is what keeps raw input out of ORDER BY) or we reject with 400 — the error
        // body enumerates the supported keys. direction is validated the same way.
        val sortKey = if (sort == null) {
            DocumentTemplateSort.UPDATED
        } else {
            DocumentTemplateSort.fromParamOrNull(sort)
                ?: throw UnsupportedSortException(sort, DocumentTemplateSort.paramValues)
        }
        val descending = SortDirection.fromParam(direction).descending
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        if (!at.isDraft) {
            // Released content is paged in memory: it is assembled whole, and bounded by the catalog.
            // Its templates share the release's timestamps, so only the name orders them.
            val templates = at.releasedResources<TemplateResource>(TEMPLATE)
                .filter { q.isNullOrBlank() || it.name.contains(q, ignoreCase = true) }
                .let { list -> if (sortKey == DocumentTemplateSort.NAME) list.sortedBy { it.name.lowercase() } else list }
                .let { if (descending && sortKey == DocumentTemplateSort.NAME) it.reversed() else it }
            val slice = Pagination.pageOfRelease(templates, page, size)
            return ResponseEntity.ok(TemplateListResponse(items = slice.items.map { it.toSummaryDto(at) }, page = slice.page))
        }
        // Large, unbounded collection: paginate at the database and get the total from a sibling
        // Count query.
        val tenantId = TenantId(at.catalog.tenantKey)
        val templates = ListDocumentTemplates(
            tenantId = tenantId,
            catalogKey = at.catalogKey,
            searchTerm = q,
            sort = sortKey,
            descending = descending,
            limit = Pagination.limitOf(size),
            offset = Pagination.offsetOf(page, size),
        ).query()
        val total = CountDocumentTemplates(tenantId = tenantId, catalogKey = at.catalogKey, searchTerm = q).query()
        return ResponseEntity.ok(
            TemplateListResponse(
                items = templates.map { it.toSummaryDto() },
                page = Pagination.pageMeta(page, size, total),
            ),
        )
    }

    override fun createTemplate(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        createTemplateRequest: CreateTemplateRequest,
    ): ResponseEntity<TemplateDto> {
        // A template keeps no description; refuse it rather than drop it.
        if (createTemplateRequest.description != null) throw ApiOperationNotImplementedException("createTemplate.description")
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val template = CreateDocumentTemplate(
            id = TemplateId(TemplateKey.of(createTemplateRequest.key), draft.catalogId),
            name = createTemplateRequest.name,
        ).execute()
        return ResponseEntity.status(HttpStatus.CREATED).body(workingCopyDto(draft, template))
    }

    override fun getTemplate(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        templateKey: String,
    ): ResponseEntity<TemplateDto> {
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val template = if (at.isDraft) {
            workingCopyDto(at, workingCopy(at, templateKey))
        } else {
            released(at, templateKey).toDto(at, objectMapper)
        }
        return ResponseEntity.ok(template)
    }

    /**
     * Updates the template's name and its data contract. The examples are always checked against the
     * schema (422 when they fail), whatever `forceUpdate` says: a template has no versions, so there
     * is no breaking change to confirm, and the contract never lets invalid examples through.
     */
    override fun updateTemplate(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        templateKey: String,
        updateTemplateRequest: UpdateTemplateRequest,
    ): ResponseEntity<TemplateDto> {
        // A template keeps no description; refuse it rather than drop it.
        if (updateTemplateRequest.description != null) throw ApiOperationNotImplementedException("updateTemplate.description")
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val id = draft.templateId(templateKey)
        val template = UpdateDocumentTemplate(id = id, name = updateTemplateRequest.name).execute()
            ?: throw TemplateNotFoundException(id.tenantKey, id.key)

        val dataModel = updateTemplateRequest.dataModel
        val requestedExamples = updateTemplateRequest.dataExamples
        if (dataModel != null || requestedExamples != null) {
            // An example keeps its identity across updates by its key.
            val current = GetDataContract(templateId = id).query()?.dataExamples.orEmpty().associateBy { it.key }
            val dataExamples = requestedExamples?.map { dto ->
                DataExample(id = current[dto.key]?.id ?: UUIDv7.generate().toString(), name = dto.name, data = dto.data, key = dto.key)
            }
            UpdateDataContract(templateId = id, dataModel = dataModel, dataExamples = dataExamples).execute()
        }
        return ResponseEntity.ok(workingCopyDto(draft, template))
    }

    /** Checked against the release preview and generation would render for the same selection. */
    override fun validateTemplateData(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        templateKey: String,
        validateTemplateDataRequest: ValidateTemplateDataRequest,
    ): ResponseEntity<TemplateDataValidationResult> {
        val catalog = catalogAt(tenantKey, registry, namespace, catalogKey)
        val analysis = AnalyzeTemplateData(
            tenantKey = catalog.tenantKey,
            catalogKey = catalog.id,
            templateId = TemplateKey.of(templateKey),
            data = validateTemplateDataRequest.data,
            variantId = validateTemplateDataRequest.variantKey?.let { VariantKey.of(it) },
            releaseVersion = validateTemplateDataRequest.version,
            environmentId = validateTemplateDataRequest.environmentKey?.let { EnvironmentKey.of(it) },
        ).query()

        val errors = analysis.invalidFields.map { TemplateDataValidationError(path = it.path, message = it.message, keyword = it.keyword) } +
            analysis.missingFields.filter { it.required }.map { TemplateDataValidationError(path = it.path, message = "is required", keyword = "required") }
        return ResponseEntity.ok(
            TemplateDataValidationResult(
                valid = analysis.valid,
                errors = errors,
                missingFields = analysis.missingFields.map {
                    MissingDataField(path = it.path, required = it.required, schema = objectMapper.convertValue(it.schema, SCHEMA_MAP))
                },
                invalidFields = analysis.invalidFields.map {
                    InvalidDataField(
                        path = it.path,
                        keyword = it.keyword,
                        message = it.message,
                        schema = it.schema?.let { schema -> objectMapper.convertValue(schema, SCHEMA_MAP) },
                    )
                },
            ),
        )
    }

    /** Marks every variant of the template ready, with its data contract. */
    override fun markTemplateReady(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        templateKey: String,
    ): ResponseEntity<TemplateDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val template = workingCopy(draft, templateKey)
        MarkTemplateReady(templateId = draft.templateId(templateKey)).execute()
        return ResponseEntity.ok(workingCopyDto(draft, template))
    }

    override fun deleteTemplate(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        templateKey: String,
    ): ResponseEntity<Unit> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val id = draft.templateId(templateKey)
        if (!DeleteDocumentTemplate(id = id).execute()) throw TemplateNotFoundException(id.tenantKey, id.key)
        return ResponseEntity.noContent().build()
    }

    // ================== Variant operations ==================

    override fun listVariants(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        templateKey: String,
        page: Int,
        size: Int,
        sort: String?,
        direction: String,
    ): ResponseEntity<VariantListResponse> {
        // This endpoint has no sortable columns; reject a caller-supplied sort rather than ignore it.
        ListSorting.rejectUnsupportedSort(sort, direction)
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val variants = if (at.isDraft) {
            val id = at.templateId(templateKey)
            workingCopy(at, templateKey)
            // Bounded per-template collection: fetch and slice in application code.
            val ready = readiness(id)
            val statuses = ResourceStatuses.of(at)
            ListVariants(templateId = id).query().map { it.toDto(statuses.of(TEMPLATE, templateKey, ready[it.id])) }
        } else {
            val template = released(at, templateKey)
            template.variants.map { it.toDto(template, at) }
        }
        val slice = Pagination.paginate(variants, page, size)
        return ResponseEntity.ok(VariantListResponse(items = slice.items, page = slice.page))
    }

    override fun createVariant(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        templateKey: String,
        createVariantRequest: CreateVariantRequest,
    ): ResponseEntity<VariantDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val templateId = draft.templateId(templateKey)
        val variant = CreateVariant(
            id = VariantId(VariantKey.of(createVariantRequest.key), templateId),
            title = createVariantRequest.title,
            description = createVariantRequest.description,
            attributes = createVariantRequest.attributes ?: emptyMap(),
        ).execute() ?: throw TemplateNotFoundException(templateId.tenantKey, templateId.key)
        return ResponseEntity.status(HttpStatus.CREATED).body(variantDto(draft, variant))
    }

    override fun getVariant(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        templateKey: String,
        variantKey: String,
    ): ResponseEntity<VariantDto> {
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val variant = if (at.isDraft) {
            variantDto(at, workingVariant(at, templateKey, variantKey))
        } else {
            val template = released(at, templateKey)
            releasedVariant(at, template, variantKey).toDto(template, at)
        }
        return ResponseEntity.ok(variant)
    }

    /** Title and attributes; attributes left out are kept. A variant's description is set when it is created. */
    override fun updateVariant(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        templateKey: String,
        variantKey: String,
        updateVariantRequest: UpdateVariantRequest,
    ): ResponseEntity<VariantDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val current = workingVariant(draft, templateKey, variantKey)
        val id = draft.variantId(templateKey, variantKey)
        val variant = UpdateVariant(
            variantId = id,
            title = updateVariantRequest.title,
            attributes = updateVariantRequest.attributes ?: current.attributes,
        ).execute() ?: throw TemplateVariantNotFoundException(id.tenantKey, id.templateKey, id.key)
        return ResponseEntity.ok(variantDto(draft, variant))
    }

    override fun deleteVariant(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        templateKey: String,
        variantKey: String,
    ): ResponseEntity<Unit> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val id = draft.variantId(templateKey, variantKey)
        if (!DeleteVariant(variantId = id).execute()) throw TemplateVariantNotFoundException(id.tenantKey, id.templateKey, id.key)
        return ResponseEntity.noContent().build()
    }

    override fun setDefaultVariant(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        templateKey: String,
        variantKey: String,
    ): ResponseEntity<VariantDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val id = draft.variantId(templateKey, variantKey)
        val variant = SetDefaultVariant(variantId = id).execute()
            ?: throw TemplateVariantNotFoundException(id.tenantKey, id.templateKey, id.key)
        return ResponseEntity.ok(variantDto(draft, variant))
    }

    // ================== Content ==================

    override fun getVariantContent(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        templateKey: String,
        variantKey: String,
    ): ResponseEntity<VariantContentDto> {
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val content = if (at.isDraft) {
            workingContent(at, templateKey, variantKey)
        } else {
            val template = released(at, templateKey)
            releasedVariant(at, template, variantKey).toContentDto(template, at)
        }
        return ResponseEntity.ok(content)
    }

    override fun updateVariantContent(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        templateKey: String,
        variantKey: String,
        updateVariantContentRequest: UpdateVariantContentRequest,
    ): ResponseEntity<VariantContentDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        workingVariant(draft, templateKey, variantKey)
        UpdateVariantContent(variantId = draft.variantId(templateKey, variantKey), templateModel = updateVariantContentRequest.templateModel).execute()
        return ResponseEntity.ok(workingContent(draft, templateKey, variantKey))
    }

    override fun markVariantReady(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        templateKey: String,
        variantKey: String,
    ): ResponseEntity<VariantContentDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        workingVariant(draft, templateKey, variantKey)
        MarkVariantReady(variantId = draft.variantId(templateKey, variantKey)).execute()
        return ResponseEntity.ok(workingContent(draft, templateKey, variantKey))
    }

    // ================== Helpers ==================

    private fun ResolvedCatalogVersion.templateId(key: String) = TemplateId(TemplateKey.of(key), catalogId)

    private fun ResolvedCatalogVersion.variantId(templateKey: String, variantKey: String) = VariantId(VariantKey.of(variantKey), templateId(templateKey))

    private fun workingCopy(at: ResolvedCatalogVersion, templateKey: String): DocumentTemplate {
        val id = at.templateId(templateKey)
        return GetDocumentTemplate(id = id).query() ?: throw TemplateNotFoundException(id.tenantKey, id.key)
    }

    private fun workingVariant(at: ResolvedCatalogVersion, templateKey: String, variantKey: String): TemplateVariant {
        val id = at.variantId(templateKey, variantKey)
        return GetVariant(variantId = id).query() ?: throw TemplateVariantNotFoundException(id.tenantKey, id.templateKey, id.key)
    }

    private fun released(at: ResolvedCatalogVersion, templateKey: String): TemplateResource = at.releasedResource<TemplateResource>(TEMPLATE, templateKey)
        ?: throw TemplateNotFoundException(at.catalog.tenantKey, TemplateKey.of(templateKey))

    private fun releasedVariant(at: ResolvedCatalogVersion, template: TemplateResource, variantKey: String) = template.variants.firstOrNull { it.key == variantKey }
        ?: throw TemplateVariantNotFoundException(at.catalog.tenantKey, TemplateKey.of(template.key), VariantKey.of(variantKey))

    /** Whether each variant of the template is marked ready as it is now. */
    private fun readiness(templateId: TemplateId): Map<VariantKey, Boolean> = GetVariantSummaries(templateId = templateId).query().associate { it.id to it.ready }

    private fun workingCopyDto(at: ResolvedCatalogVersion, template: DocumentTemplate): TemplateDto {
        val id = at.templateId(template.id.value)
        return template.toDto(
            objectMapper,
            GetVariantSummaries(templateId = id).query(),
            GetDataContract(templateId = id).query(),
            ResourceStatuses.of(at),
        )
    }

    private fun variantDto(at: ResolvedCatalogVersion, variant: TemplateVariant): VariantDto {
        val ready = readiness(at.templateId(variant.templateKey.value))[variant.id]
        return variant.toDto(ResourceStatuses.of(at).of(TEMPLATE, variant.templateKey.value, ready))
    }

    /** The variant's working model, with its status. When it was marked ready is not recorded where this reads it. */
    private fun workingContent(at: ResolvedCatalogVersion, templateKey: String, variantKey: String): VariantContentDto {
        val variant = workingVariant(at, templateKey, variantKey)
        val id = at.variantId(templateKey, variantKey)
        val model = GetVariantContent(variantId = id).query()
            ?: throw TemplateVariantNotFoundException(id.tenantKey, id.templateKey, id.key)
        val ready = readiness(at.templateId(templateKey))[variant.id]
        return VariantContentDto(
            variantKey = variant.id.value,
            templateModel = model,
            status = ResourceStatuses.of(at).of(TEMPLATE, templateKey, ready),
            lastModified = variant.updatedAt,
            readyAt = null,
        )
    }

    private companion object {
        const val TEMPLATE = "template"
        val SCHEMA_MAP = object : TypeReference<Map<String, Any>>() {}
    }
}

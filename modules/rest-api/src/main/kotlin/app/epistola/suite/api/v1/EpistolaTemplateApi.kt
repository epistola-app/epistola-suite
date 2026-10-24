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
import app.epistola.suite.api.v1.shared.ETags
import app.epistola.suite.api.v1.shared.ListSorting
import app.epistola.suite.api.v1.shared.Pagination
import app.epistola.suite.api.v1.shared.ResourceStatuses
import app.epistola.suite.api.v1.shared.SortDirection
import app.epistola.suite.api.v1.shared.UnsupportedSortException
import app.epistola.suite.api.v1.shared.catalogAt
import app.epistola.suite.api.v1.shared.catalogId
import app.epistola.suite.api.v1.shared.catalogVersionAt
import app.epistola.suite.api.v1.shared.created
import app.epistola.suite.api.v1.shared.draftAt
import app.epistola.suite.api.v1.shared.okWithETag
import app.epistola.suite.api.v1.shared.ref
import app.epistola.suite.api.v1.shared.releasedResource
import app.epistola.suite.api.v1.shared.releasedResources
import app.epistola.suite.api.v1.shared.toContentDto
import app.epistola.suite.api.v1.shared.toDto
import app.epistola.suite.api.v1.shared.toSummaryDto
import app.epistola.suite.catalog.releases.ResolvedCatalogRelease
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
import app.epistola.suite.templates.queries.variants.GetVariantContentAtRevision
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
        release: String,
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
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, release)
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
            catalog = at.catalogId,
            searchTerm = q,
            sort = sortKey,
            descending = descending,
            limit = Pagination.limitOf(size),
            offset = Pagination.offsetOf(page, size),
            catalogVersion = at.ref,
        ).query()
        val total = CountDocumentTemplates(tenantId = TenantId(at.catalog.tenantKey), catalog = at.catalogId, searchTerm = q, catalogVersion = at.ref).query()
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
        release: String,
        createTemplateRequest: CreateTemplateRequest,
    ): ResponseEntity<TemplateDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        val template = CreateDocumentTemplate(
            id = TemplateId(TemplateKey.of(createTemplateRequest.key), draft.catalogId),
            name = createTemplateRequest.name,
            catalogVersion = draft.ref,
            description = createTemplateRequest.description,
        ).execute()
        return workingCopyDto(draft, template).let { created(it.key, it) }
    }

    override fun getTemplate(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        templateKey: String,
    ): ResponseEntity<TemplateDto> {
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, release)
        if (!at.isDraft) return ResponseEntity.ok(released(at, templateKey).toDto(at, objectMapper))
        val (dto, revision) = workingCopyAtRevision(at, templateKey)
        return okWithETag(revision, dto)
    }

    /**
     * Updates the template's name, description and its data contract. The examples are always checked against the
     * schema (422 when they fail), whatever `forceUpdate` says: a template has no versions, so there
     * is no breaking change to confirm, and the contract never lets invalid examples through.
     */
    override fun updateTemplate(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        templateKey: String,
        updateTemplateRequest: UpdateTemplateRequest,
        ifMatch: String?,
    ): ResponseEntity<TemplateDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        val id = draft.templateId(templateKey)
        val template = UpdateDocumentTemplate(
            id = id,
            name = updateTemplateRequest.name,
            // The generated request cannot tell an omitted description from an explicit null, so null
            // keeps the current one and an empty string removes it.
            description = updateTemplateRequest.description,
            catalogVersion = draft.ref,
            expectedRevision = ETags.expected(ifMatch),
        ).execute()
            ?: throw TemplateNotFoundException(id.tenantKey, id.key)
        // The contract is part of the template: written against the revision the name change left.
        var revision = template.revision

        val dataModel = updateTemplateRequest.dataModel
        val requestedExamples = updateTemplateRequest.dataExamples
        if (dataModel != null || requestedExamples != null) {
            // An example keeps its identity across updates by its key.
            val current = GetDataContract(templateId = id, catalogVersion = draft.ref).query()?.dataExamples.orEmpty().associateBy { it.key }
            val dataExamples = requestedExamples?.map { dto ->
                DataExample(id = current[dto.key]?.id ?: UUIDv7.generate().toString(), name = dto.name, data = dto.data, key = dto.key)
            }
            revision = UpdateDataContract(templateId = id, dataModel = dataModel, dataExamples = dataExamples, catalogVersion = draft.ref, expectedRevision = revision).execute().revision
        }
        return okWithETag(revision, workingCopyDto(draft, workingCopy(draft, templateKey)))
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
            catalog = catalog.catalogId,
            templateId = TemplateKey.of(templateKey),
            data = validateTemplateDataRequest.data,
            variantId = validateTemplateDataRequest.variantKey?.let { VariantKey.of(it) },
            releaseVersion = validateTemplateDataRequest.versionRange,
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
        release: String,
        templateKey: String,
    ): ResponseEntity<TemplateDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        val template = workingCopy(draft, templateKey)
        MarkTemplateReady(templateId = draft.templateId(templateKey), catalogVersion = draft.ref).execute()
        return ResponseEntity.ok(workingCopyDto(draft, template))
    }

    override fun deleteTemplate(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        templateKey: String,
    ): ResponseEntity<Unit> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        val id = draft.templateId(templateKey)
        if (!DeleteDocumentTemplate(id = id, catalogVersion = draft.ref).execute()) throw TemplateNotFoundException(id.tenantKey, id.key)
        return ResponseEntity.noContent().build()
    }

    // ================== Variant operations ==================

    override fun listVariants(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        templateKey: String,
        page: Int,
        size: Int,
        sort: String?,
        direction: String,
    ): ResponseEntity<VariantListResponse> {
        // This endpoint has no sortable columns; reject a caller-supplied sort rather than ignore it.
        ListSorting.rejectUnsupportedSort(sort, direction)
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, release)
        val variants = if (at.isDraft) {
            val id = at.templateId(templateKey)
            workingCopy(at, templateKey)
            // Bounded per-template collection: fetch and slice in application code.
            val ready = readiness(at, id)
            val statuses = ResourceStatuses.of(at)
            ListVariants(templateId = id, catalogVersion = at.ref).query().map { it.toDto(statuses.of(TEMPLATE, templateKey, ready[it.id])) }
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
        release: String,
        templateKey: String,
        createVariantRequest: CreateVariantRequest,
    ): ResponseEntity<VariantDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        val templateId = draft.templateId(templateKey)
        val variant = CreateVariant(
            id = VariantId(VariantKey.of(createVariantRequest.key), templateId),
            title = createVariantRequest.title,
            description = createVariantRequest.description,
            attributes = createVariantRequest.attributes ?: emptyMap(),
            catalogVersion = draft.ref,
        ).execute() ?: throw TemplateNotFoundException(templateId.tenantKey, templateId.key)
        return variantDto(draft, variant).let { created(it.key, it) }
    }

    override fun getVariant(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        templateKey: String,
        variantKey: String,
    ): ResponseEntity<VariantDto> {
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, release)
        if (!at.isDraft) {
            val template = released(at, templateKey)
            return ResponseEntity.ok(releasedVariant(at, template, variantKey).toDto(template, at))
        }
        val variant = workingVariant(at, templateKey, variantKey)
        return okWithETag(variant.revision, variantDto(at, variant))
    }

    /** Title and attributes; attributes left out are kept. A variant's description is set when it is created. */
    override fun updateVariant(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        templateKey: String,
        variantKey: String,
        updateVariantRequest: UpdateVariantRequest,
        ifMatch: String?,
    ): ResponseEntity<VariantDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        val current = workingVariant(draft, templateKey, variantKey)
        val id = draft.variantId(templateKey, variantKey)
        val variant = UpdateVariant(
            variantId = id,
            title = updateVariantRequest.title,
            attributes = updateVariantRequest.attributes ?: current.attributes,
            catalogVersion = draft.ref,
            // The merge above read the variant at `current`; a write in between must not be lost.
            expectedRevision = ETags.expected(ifMatch) ?: current.revision,
        ).execute() ?: throw TemplateVariantNotFoundException(id.tenantKey, id.templateKey, id.key)
        return okWithETag(variant.revision, variantDto(draft, variant))
    }

    override fun deleteVariant(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        templateKey: String,
        variantKey: String,
    ): ResponseEntity<Unit> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        val id = draft.variantId(templateKey, variantKey)
        if (!DeleteVariant(variantId = id, catalogVersion = draft.ref).execute()) throw TemplateVariantNotFoundException(id.tenantKey, id.templateKey, id.key)
        return ResponseEntity.noContent().build()
    }

    override fun setDefaultVariant(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        templateKey: String,
        variantKey: String,
        ifMatch: String?,
    ): ResponseEntity<VariantDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        val id = draft.variantId(templateKey, variantKey)
        val variant = SetDefaultVariant(variantId = id, catalogVersion = draft.ref, expectedRevision = ETags.expected(ifMatch)).execute()
            ?: throw TemplateVariantNotFoundException(id.tenantKey, id.templateKey, id.key)
        return okWithETag(variant.revision, variantDto(draft, variant))
    }

    // ================== Content ==================

    override fun getVariantContent(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        templateKey: String,
        variantKey: String,
    ): ResponseEntity<VariantContentDto> {
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, release)
        if (!at.isDraft) {
            val template = released(at, templateKey)
            return ResponseEntity.ok(releasedVariant(at, template, variantKey).toContentDto(template, at))
        }
        val (dto, revision) = workingContent(at, templateKey, variantKey)
        return okWithETag(revision, dto)
    }

    override fun updateVariantContent(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        templateKey: String,
        variantKey: String,
        updateVariantContentRequest: UpdateVariantContentRequest,
        ifMatch: String?,
    ): ResponseEntity<VariantContentDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        workingVariant(draft, templateKey, variantKey)
        val saved = UpdateVariantContent(
            variantId = draft.variantId(templateKey, variantKey),
            templateModel = updateVariantContentRequest.templateModel,
            catalogVersion = draft.ref,
            expectedRevision = ETags.expected(ifMatch),
        ).execute()
        return okWithETag(saved.revision, workingContent(draft, templateKey, variantKey).first)
    }

    override fun markVariantReady(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        templateKey: String,
        variantKey: String,
    ): ResponseEntity<VariantContentDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        workingVariant(draft, templateKey, variantKey)
        MarkVariantReady(variantId = draft.variantId(templateKey, variantKey), catalogVersion = draft.ref).execute()
        val (dto, revision) = workingContent(draft, templateKey, variantKey)
        return okWithETag(revision, dto)
    }

    // ================== Helpers ==================

    private fun ResolvedCatalogRelease.templateId(key: String) = TemplateId(TemplateKey.of(key), catalogId)

    private fun ResolvedCatalogRelease.variantId(templateKey: String, variantKey: String) = VariantId(VariantKey.of(variantKey), templateId(templateKey))

    private fun workingCopy(at: ResolvedCatalogRelease, templateKey: String): DocumentTemplate {
        val id = at.templateId(templateKey)
        return GetDocumentTemplate(id = id, catalogVersion = at.ref).query() ?: throw TemplateNotFoundException(id.tenantKey, id.key)
    }

    private fun workingVariant(at: ResolvedCatalogRelease, templateKey: String, variantKey: String): TemplateVariant {
        val id = at.variantId(templateKey, variantKey)
        return GetVariant(variantId = id, catalogVersion = at.ref).query() ?: throw TemplateVariantNotFoundException(id.tenantKey, id.templateKey, id.key)
    }

    private fun released(at: ResolvedCatalogRelease, templateKey: String): TemplateResource = at.releasedResource<TemplateResource>(TEMPLATE, templateKey)
        ?: throw TemplateNotFoundException(at.catalog.tenantKey, TemplateKey.of(templateKey))

    private fun releasedVariant(at: ResolvedCatalogRelease, template: TemplateResource, variantKey: String) = template.variants.firstOrNull { it.key == variantKey }
        ?: throw TemplateVariantNotFoundException(at.catalog.tenantKey, TemplateKey.of(template.key), VariantKey.of(variantKey))

    /** Whether each variant of the template is marked ready as it is now. */
    private fun readiness(at: ResolvedCatalogRelease, templateId: TemplateId): Map<VariantKey, Boolean> = GetVariantSummaries(templateId = templateId, catalogVersion = at.ref).query().associate { it.id to it.ready }

    /**
     * The template's working copy and its revision. The template and its data contract are read
     * separately; when a write lands in between, they disagree on the revision and are read again, so
     * the ETag never claims a contract the body does not show.
     */
    private fun workingCopyAtRevision(at: ResolvedCatalogRelease, templateKey: String): Pair<TemplateDto, Long> {
        repeat(2) {
            val template = workingCopy(at, templateKey)
            val contract = GetDataContract(templateId = at.templateId(templateKey), catalogVersion = at.ref).query()
            if (contract == null || contract.revision == template.revision) return workingCopyDto(at, template, contract) to template.revision
        }
        val template = workingCopy(at, templateKey)
        return workingCopyDto(at, template) to template.revision
    }

    private fun workingCopyDto(
        at: ResolvedCatalogRelease,
        template: DocumentTemplate,
        contract: app.epistola.suite.templates.model.DataContract? = GetDataContract(templateId = at.templateId(template.id.value), catalogVersion = at.ref).query(),
    ): TemplateDto {
        val id = at.templateId(template.id.value)
        return template.toDto(
            objectMapper,
            GetVariantSummaries(templateId = id, catalogVersion = at.ref).query(),
            contract,
            ResourceStatuses.of(at),
        )
    }

    private fun variantDto(at: ResolvedCatalogRelease, variant: TemplateVariant): VariantDto {
        val ready = readiness(at, at.templateId(variant.templateKey.value))[variant.id]
        return variant.toDto(ResourceStatuses.of(at).of(TEMPLATE, variant.templateKey.value, ready))
    }

    /** The variant's working content, with the revision the model was read at. */
    private fun workingContent(at: ResolvedCatalogRelease, templateKey: String, variantKey: String): Pair<VariantContentDto, Long> {
        val variant = workingVariant(at, templateKey, variantKey)
        val id = at.variantId(templateKey, variantKey)
        val content = GetVariantContentAtRevision(variantId = id, catalogVersion = at.ref).query()
            ?: throw TemplateVariantNotFoundException(id.tenantKey, id.templateKey, id.key)
        val ready = readiness(at, at.templateId(templateKey))[variant.id]
        return VariantContentDto(
            variantKey = variant.id.value,
            templateModel = content.templateModel,
            status = ResourceStatuses.of(at).of(TEMPLATE, templateKey, ready),
            lastModified = variant.updatedAt,
            readyAt = null,
        ) to content.revision
    }

    private companion object {
        const val TEMPLATE = "template"
        val SCHEMA_MAP = object : TypeReference<Map<String, Any>>() {}
    }
}

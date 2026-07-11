package app.epistola.suite.api.v1

import app.epistola.api.TemplatesApi
import app.epistola.api.VariantsApi
import app.epistola.api.VersionsApi
import app.epistola.api.model.ActivationListResponse
import app.epistola.api.model.CheckRecentUsageRequest
import app.epistola.api.model.CreateTemplateRequest
import app.epistola.api.model.CreateVariantRequest
import app.epistola.api.model.PublishVersionRequest
import app.epistola.api.model.TemplateDataValidationError
import app.epistola.api.model.TemplateDataValidationResult
import app.epistola.api.model.TemplateDto
import app.epistola.api.model.TemplateListResponse
import app.epistola.api.model.UpdateDraftRequest
import app.epistola.api.model.UpdateTemplateRequest
import app.epistola.api.model.UpdateVariantRequest
import app.epistola.api.model.ValidateTemplateDataRequest
import app.epistola.api.model.VariantDto
import app.epistola.api.model.VariantListResponse
import app.epistola.api.model.VersionDto
import app.epistola.api.model.VersionListResponse
import app.epistola.suite.api.v1.shared.ListSorting
import app.epistola.suite.api.v1.shared.Pagination
import app.epistola.suite.api.v1.shared.SortDirection
import app.epistola.suite.api.v1.shared.UnsupportedSortException
import app.epistola.suite.api.v1.shared.VariantVersionInfo
import app.epistola.suite.api.v1.shared.toDto
import app.epistola.suite.api.v1.shared.toSummaryDto
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.documents.TemplateVariantNotFoundException
import app.epistola.suite.documents.VersionNotFoundException
import app.epistola.suite.documents.queries.CheckRecentUsageCompatibility
import app.epistola.suite.documents.queries.RecentUsageImpact
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.DraftNotFoundException
import app.epistola.suite.templates.NoActiveVersionException
import app.epistola.suite.templates.TemplateNotFoundException
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.DeleteDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.activations.RemoveActivation
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.DeleteVariant
import app.epistola.suite.templates.commands.variants.SetDefaultVariant
import app.epistola.suite.templates.commands.variants.UpdateVariant
import app.epistola.suite.templates.commands.versions.ArchiveVersion
import app.epistola.suite.templates.commands.versions.PublishToEnvironment
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.commands.versions.UpdateVersion
import app.epistola.suite.templates.contracts.ContractPublishConflictException
import app.epistola.suite.templates.contracts.commands.CreateContractVersion
import app.epistola.suite.templates.contracts.commands.PublishContractVersion
import app.epistola.suite.templates.contracts.commands.UpdateContractVersion
import app.epistola.suite.templates.contracts.queries.GetLatestContractVersion
import app.epistola.suite.templates.contracts.queries.GetLatestPublishedContractVersion
import app.epistola.suite.templates.contracts.queries.PreviewContractUpdate
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.model.TemplateVariant
import app.epistola.suite.templates.model.VersionStatus
import app.epistola.suite.templates.queries.CountDocumentTemplates
import app.epistola.suite.templates.queries.DocumentTemplateSort
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.templates.queries.activations.GetActiveVersion
import app.epistola.suite.templates.queries.activations.ListActivations
import app.epistola.suite.templates.queries.variants.GetVariant
import app.epistola.suite.templates.queries.variants.GetVariantSummaries
import app.epistola.suite.templates.queries.variants.ListVariants
import app.epistola.suite.templates.queries.versions.GetDraft
import app.epistola.suite.templates.queries.versions.GetVersion
import app.epistola.suite.templates.queries.versions.ListVersions
import app.epistola.suite.templates.validation.DataModelValidationException
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.validation.ValidationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import app.epistola.api.model.RecentUsageFieldImpact as RecentUsageFieldImpactDto
import app.epistola.api.model.RecentUsageImpact as RecentUsageImpactDto

@RestController
@RequestMapping("/api")
class EpistolaTemplateApi(
    private val objectMapper: ObjectMapper,
    private val jsonSchemaValidator: JsonSchemaValidator,
) : TemplatesApi,
    VariantsApi,
    VersionsApi {

    // ================== Template operations ==================

    override fun listTemplates(
        tenantId: String,
        catalogId: String,
        q: String?,
        page: Int,
        size: Int,
        sort: String?,
        direction: String,
    ): ResponseEntity<TemplateListResponse> {
        // Large, unbounded collection: paginate at the database and get the total
        // from a sibling Count query (see EpistolaDocumentGenerationApi for the
        // documents/jobs variant of this pattern).
        val tid = TenantId(TenantKey.of(tenantId))
        val catalogKey = CatalogKey.of(catalogId)
        // The contract does no validation on sort/direction (free-form strings), so we do it here.
        // An absent sort selects the default order; a non-null value must name a whitelisted field
        // (the whitelist is what keeps raw input out of ORDER BY) or we reject with 400 — the error
        // body enumerates the supported keys, which the contract no longer advertises. direction is
        // validated the same way by SortDirection.fromParam below: absent → the default desc, an
        // unrecognized non-null value → 400.
        val sortKey = if (sort == null) {
            DocumentTemplateSort.UPDATED
        } else {
            DocumentTemplateSort.fromParamOrNull(sort)
                ?: throw UnsupportedSortException(sort, DocumentTemplateSort.paramValues)
        }
        val templates = ListDocumentTemplates(
            tenantId = tid,
            catalogKey = catalogKey,
            searchTerm = q,
            sort = sortKey,
            descending = SortDirection.fromParam(direction).descending,
            limit = Pagination.limitOf(size),
            offset = Pagination.offsetOf(page, size),
        ).query()
        val total = CountDocumentTemplates(tenantId = tid, catalogKey = catalogKey, searchTerm = q).query()
        return ResponseEntity.ok(
            TemplateListResponse(
                items = templates.map { it.toSummaryDto() },
                page = Pagination.pageMeta(page, size, total),
            ),
        )
    }

    override fun createTemplate(
        tenantId: String,
        catalogId: String,
        createTemplateRequest: CreateTemplateRequest,
    ): ResponseEntity<TemplateDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val template = CreateDocumentTemplate(
            id = TemplateId(TemplateKey.of(createTemplateRequest.id), CatalogId(CatalogKey.of(catalogId), tenantIdComposite)),
            name = createTemplateRequest.name,
        ).execute()
        val templateIdComposite = TemplateId(template.id, CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val variantSummaries = GetVariantSummaries(templateId = templateIdComposite).query()
        return ResponseEntity.status(HttpStatus.CREATED).body(template.toDto(objectMapper, variantSummaries))
    }

    override fun getTemplate(
        tenantId: String,
        catalogId: String,
        templateId: String,
    ): ResponseEntity<TemplateDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val template = GetDocumentTemplate(id = templateIdComposite).query()
            ?: throw TemplateNotFoundException(tenantIdComposite.key, templateIdComposite.key)
        val variantSummaries = GetVariantSummaries(templateId = templateIdComposite).query()
        val contractVersion = GetLatestPublishedContractVersion(templateId = templateIdComposite).query()
        return ResponseEntity.ok(template.toDto(objectMapper, variantSummaries, contractVersion))
    }

    override fun updateTemplate(
        tenantId: String,
        catalogId: String,
        templateId: String,
        updateTemplateRequest: UpdateTemplateRequest,
    ): ResponseEntity<TemplateDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val template = UpdateDocumentTemplate(
            id = templateIdComposite,
            name = updateTemplateRequest.name,
        ).execute() ?: throw TemplateNotFoundException(tenantIdComposite.key, templateIdComposite.key)

        val dataModel = updateTemplateRequest.dataModel
        val dataExamples = updateTemplateRequest.dataExamples?.map { dto ->
            DataExample(id = dto.id, name = dto.name, data = dto.data)
        }
        if (dataModel != null || dataExamples != null) {
            val confirmBreaking = updateTemplateRequest.forceUpdate ?: false

            // Decide accept/reject up front, before any draft is created. The
            // create -> update -> publish commands below each commit in their own
            // transaction, so rejecting only at publish time would leave a dangling
            // draft contract version behind (observable to validation/preview reads).
            val preview = PreviewContractUpdate(
                templateId = templateIdComposite,
                dataModel = dataModel,
                dataExamples = dataExamples,
            ).query()
            // Data examples must always be valid against the schema — a published
            // contract is never allowed to carry examples it would reject (422).
            if (preview.exampleValidationErrors.isNotEmpty()) {
                throw DataModelValidationException(preview.exampleValidationErrors)
            }
            // A backwards-incompatible schema change is only published when the caller
            // confirms it via forceUpdate; otherwise surface a 409 — carrying whether the change
            // would break the input data of recent generations (#280).
            if (!preview.compatible && !confirmBreaking) {
                val recentUsage = dataModel?.let {
                    CheckRecentUsageCompatibility(templateId = templateIdComposite, candidateSchema = it).query()
                }
                throw ContractPublishConflictException(preview.breakingChanges, recentUsage)
            }

            // The REST caller is authoritative: stage the change on a draft, then publish it
            // immediately so the change is readable back via GET (read-your-write).
            CreateContractVersion(templateId = templateIdComposite).execute()
                ?: throw TemplateNotFoundException(tenantIdComposite.key, templateIdComposite.key)
            UpdateContractVersion(
                templateId = templateIdComposite,
                dataModel = dataModel,
                dataExamples = dataExamples,
                forceUpdate = false,
            ).execute() ?: throw TemplateNotFoundException(tenantIdComposite.key, templateIdComposite.key)
            val publish = PublishContractVersion(templateId = templateIdComposite, confirmed = confirmBreaking).execute()
            // Backstop for a concurrent change landing between the preview and the publish.
            if (publish != null && !publish.published) {
                throw ContractPublishConflictException(publish.breakingChanges.map { it.description })
            }
        }

        val variantSummaries = GetVariantSummaries(templateId = templateIdComposite).query()
        val contractVersion = GetLatestPublishedContractVersion(templateId = templateIdComposite).query()
        return ResponseEntity.ok(template.toDto(objectMapper, variantSummaries, contractVersion))
    }

    override fun validateTemplateData(
        tenantId: String,
        catalogId: String,
        templateId: String,
        validateTemplateDataRequest: ValidateTemplateDataRequest,
    ): ResponseEntity<TemplateDataValidationResult> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        GetDocumentTemplate(id = templateIdComposite).query()
            ?: throw TemplateNotFoundException(tenantIdComposite.key, templateIdComposite.key)

        val contractVersion = GetLatestContractVersion(templateId = templateIdComposite).query()
        val dataModel = contractVersion?.dataModel
            ?: return ResponseEntity.ok(TemplateDataValidationResult(valid = true, errors = emptyList()))

        val dataNode = objectMapper.valueToTree<ObjectNode>(validateTemplateDataRequest.data)
        val errors = jsonSchemaValidator.validate(dataModel, dataNode)

        return ResponseEntity.ok(
            TemplateDataValidationResult(
                valid = errors.isEmpty(),
                errors = errors.map { error ->
                    TemplateDataValidationError(
                        path = error.path,
                        message = error.message,
                    )
                },
            ),
        )
    }

    override fun checkRecentUsageImpact(
        tenantId: String,
        catalogId: String,
        templateId: String,
        checkRecentUsageRequest: CheckRecentUsageRequest,
    ): ResponseEntity<RecentUsageImpactDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        GetDocumentTemplate(id = templateIdComposite).query()
            ?: throw TemplateNotFoundException(tenantIdComposite.key, templateIdComposite.key)

        val candidate = objectMapper.valueToTree<ObjectNode>(checkRecentUsageRequest.dataModel)
        val impact = CheckRecentUsageCompatibility(
            templateId = templateIdComposite,
            candidateSchema = candidate,
        ).query()

        return ResponseEntity.ok(impact.toDto())
    }

    override fun deleteTemplate(
        tenantId: String,
        catalogId: String,
        templateId: String,
    ): ResponseEntity<Unit> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val deleted = DeleteDocumentTemplate(id = templateIdComposite).execute()
        return if (deleted) {
            ResponseEntity.noContent().build()
        } else {
            throw TemplateNotFoundException(tenantIdComposite.key, templateIdComposite.key)
        }
    }

    // ================== Variant operations ==================

    override fun listVariants(
        tenantId: String,
        catalogId: String,
        templateId: String,
        page: Int,
        size: Int,
        sort: String?,
        direction: String,
    ): ResponseEntity<VariantListResponse> {
        // This endpoint has no sortable columns; reject a caller-supplied sort rather than ignore it.
        ListSorting.rejectUnsupportedSort(sort, direction)
        val typedTenantId = TenantKey.of(tenantId)
        val tenantIdComposite = TenantId(typedTenantId)
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        // Bounded per-template collection: fetch and slice in application code.
        val variants = ListVariants(templateId = templateIdComposite).query()
        val slice = Pagination.paginate(variants, page, size)
        val variantDtos = slice.items.map { variant ->
            val summary = getVariantSummary(variant, typedTenantId, catalogId)
            variant.toDto(summary)
        }
        return ResponseEntity.ok(VariantListResponse(items = variantDtos, page = slice.page))
    }

    override fun createVariant(
        tenantId: String,
        catalogId: String,
        templateId: String,
        createVariantRequest: CreateVariantRequest,
    ): ResponseEntity<VariantDto> {
        val typedTenantId = TenantKey.of(tenantId)
        val tenantIdComposite = TenantId(typedTenantId)
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val variantIdComposite = VariantId(VariantKey.of(createVariantRequest.id), templateIdComposite)
        val variant = CreateVariant(
            id = variantIdComposite,
            title = createVariantRequest.title,
            description = createVariantRequest.description,
            attributes = createVariantRequest.attributes ?: emptyMap(),
        ).execute() ?: throw TemplateNotFoundException(tenantIdComposite.key, templateIdComposite.key)
        val summary = getVariantSummary(variant, typedTenantId, catalogId)
        return ResponseEntity.status(HttpStatus.CREATED).body(variant.toDto(summary))
    }

    override fun getVariant(
        tenantId: String,
        catalogId: String,
        templateId: String,
        variantId: String,
    ): ResponseEntity<VariantDto> {
        val typedTenantId = TenantKey.of(tenantId)
        val tenantIdComposite = TenantId(typedTenantId)
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val variantIdComposite = VariantId(VariantKey.of(variantId), templateIdComposite)
        val variant = GetVariant(variantId = variantIdComposite).query()
            ?: throw TemplateVariantNotFoundException(typedTenantId, templateIdComposite.key, variantIdComposite.key)
        val summary = getVariantSummary(variant, typedTenantId, catalogId)
        return ResponseEntity.ok(variant.toDto(summary))
    }

    override fun updateVariant(
        tenantId: String,
        catalogId: String,
        templateId: String,
        variantId: String,
        updateVariantRequest: UpdateVariantRequest,
    ): ResponseEntity<VariantDto> {
        val attributes = updateVariantRequest.attributes
            ?: throw ValidationException(field = "attributes", message = "Variant attributes are required")
        val typedTenantId = TenantKey.of(tenantId)
        val tenantIdComposite = TenantId(typedTenantId)
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val variantIdComposite = VariantId(VariantKey.of(variantId), templateIdComposite)
        val variant = UpdateVariant(
            variantId = variantIdComposite,
            title = updateVariantRequest.title,
            attributes = attributes,
        ).execute() ?: throw TemplateVariantNotFoundException(typedTenantId, templateIdComposite.key, variantIdComposite.key)
        val summary = getVariantSummary(variant, typedTenantId, catalogId)
        return ResponseEntity.ok(variant.toDto(summary))
    }

    override fun deleteVariant(
        tenantId: String,
        catalogId: String,
        templateId: String,
        variantId: String,
    ): ResponseEntity<Unit> {
        val typedTenantId = TenantKey.of(tenantId)
        val tenantIdComposite = TenantId(typedTenantId)
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val variantIdComposite = VariantId(VariantKey.of(variantId), templateIdComposite)
        val deleted = DeleteVariant(
            variantId = variantIdComposite,
        ).execute()
        return if (deleted) {
            ResponseEntity.noContent().build()
        } else {
            throw TemplateVariantNotFoundException(typedTenantId, templateIdComposite.key, variantIdComposite.key)
        }
    }

    override fun setDefaultVariant(
        tenantId: String,
        catalogId: String,
        templateId: String,
        variantId: String,
    ): ResponseEntity<VariantDto> {
        val typedTenantId = TenantKey.of(tenantId)
        val tenantIdComposite = TenantId(typedTenantId)
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val variantIdComposite = VariantId(VariantKey.of(variantId), templateIdComposite)
        val variant = SetDefaultVariant(
            variantId = variantIdComposite,
        ).execute() ?: throw TemplateVariantNotFoundException(typedTenantId, templateIdComposite.key, variantIdComposite.key)
        val summary = getVariantSummary(variant, typedTenantId, catalogId)
        return ResponseEntity.ok(variant.toDto(summary))
    }

    // ================== Draft operations ==================

    override fun getVariantDraft(
        tenantId: String,
        catalogId: String,
        templateId: String,
        variantId: String,
    ): ResponseEntity<VersionDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val variantIdComposite = VariantId(VariantKey.of(variantId), templateIdComposite)
        // Verify variant exists before distinguishing "variant not found" from "no draft"
        GetVariant(variantId = variantIdComposite).query()
            ?: throw TemplateVariantNotFoundException(tenantIdComposite.key, templateIdComposite.key, variantIdComposite.key)
        val draft = GetDraft(variantId = variantIdComposite).query()
            ?: throw DraftNotFoundException(tenantIdComposite.key, variantIdComposite.key)
        return ResponseEntity.ok(draft.toDto(objectMapper))
    }

    override fun upsertVariantDraft(
        tenantId: String,
        catalogId: String,
        templateId: String,
        variantId: String,
        updateDraftRequest: UpdateDraftRequest,
    ): ResponseEntity<VersionDto> {
        val templateModel = updateDraftRequest.templateModel?.let {
            objectMapper.treeToValue(objectMapper.valueToTree(it), app.epistola.suite.templates.model.TemplateDocument::class.java)
        } ?: throw ValidationException(field = "templateModel", message = "Template model is required")
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val variantIdComposite = VariantId(VariantKey.of(variantId), templateIdComposite)
        val draft = UpdateDraft(
            variantId = variantIdComposite,
            templateModel = templateModel,
        ).execute() ?: throw TemplateVariantNotFoundException(tenantIdComposite.key, templateIdComposite.key, variantIdComposite.key)
        return ResponseEntity.ok(draft.toDto(objectMapper))
    }

    // ================== Activation operations ==================

    override fun listVariantActivations(
        tenantId: String,
        catalogId: String,
        templateId: String,
        variantId: String,
    ): ResponseEntity<ActivationListResponse> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val variantIdComposite = VariantId(VariantKey.of(variantId), templateIdComposite)
        val activations = ListActivations(variantId = variantIdComposite).query()
        return ResponseEntity.ok(ActivationListResponse(items = activations.map { it.toDto() }))
    }

    override fun removeVariantActivation(
        tenantId: String,
        catalogId: String,
        templateId: String,
        variantId: String,
        environmentId: String,
    ): ResponseEntity<Unit> {
        val typedTenantId = TenantKey.of(tenantId)
        val tenantIdComposite = TenantId(typedTenantId)
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val variantIdComposite = VariantId(VariantKey.of(variantId), templateIdComposite)
        val environmentIdComposite = EnvironmentId(EnvironmentKey.of(environmentId), tenantIdComposite)
        RemoveActivation(
            variantId = variantIdComposite,
            environmentId = environmentIdComposite,
        ).execute()
        return ResponseEntity.noContent().build()
    }

    override fun getActiveVersion(
        environment: String,
        tenantId: String,
        catalogId: String,
        templateId: String,
        variantId: String,
    ): ResponseEntity<VersionDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val variantIdComposite = VariantId(VariantKey.of(variantId), templateIdComposite)
        val environmentIdComposite = EnvironmentId(EnvironmentKey.of(environment), tenantIdComposite)
        // Verify variant exists before distinguishing "variant not found" from "no active version"
        GetVariant(variantId = variantIdComposite).query()
            ?: throw TemplateVariantNotFoundException(tenantIdComposite.key, templateIdComposite.key, variantIdComposite.key)
        val version = GetActiveVersion(variantId = variantIdComposite, environmentId = environmentIdComposite).query()
            ?: throw NoActiveVersionException(tenantIdComposite.key, variantIdComposite.key, environmentIdComposite.key)
        return ResponseEntity.ok(version.toDto(objectMapper))
    }

    // ================== Version operations ==================

    override fun listVersions(
        tenantId: String,
        catalogId: String,
        templateId: String,
        variantId: String,
        status: String?,
        page: Int,
        size: Int,
        sort: String?,
        direction: String,
    ): ResponseEntity<VersionListResponse> {
        // This endpoint has no sortable columns; reject a caller-supplied sort rather than ignore it.
        ListSorting.rejectUnsupportedSort(sort, direction)
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val variantIdComposite = VariantId(VariantKey.of(variantId), templateIdComposite)
        val versions = ListVersions(variantId = variantIdComposite).query()
        // Bounded per-variant collection: filter, then slice in application code so
        // the total reflects the status filter.
        val filteredVersions = if (status != null) {
            versions.filter { it.status.name.equals(status, ignoreCase = true) }
        } else {
            versions
        }
        val slice = Pagination.paginate(filteredVersions, page, size)
        return ResponseEntity.ok(
            VersionListResponse(
                items = slice.items.map { it.toSummaryDto() },
                page = slice.page,
            ),
        )
    }

    override fun getVersion(
        tenantId: String,
        catalogId: String,
        templateId: String,
        variantId: String,
        versionId: Int,
    ): ResponseEntity<VersionDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val variantIdComposite = VariantId(VariantKey.of(variantId), templateIdComposite)
        val versionIdComposite = VersionId(VersionKey.of(versionId), variantIdComposite)
        val version = GetVersion(versionId = versionIdComposite).query()
            ?: throw VersionNotFoundException(tenantIdComposite.key, templateIdComposite.key, variantIdComposite.key, versionIdComposite.key)
        return ResponseEntity.ok(version.toDto(objectMapper))
    }

    override fun updateVersion(
        tenantId: String,
        catalogId: String,
        templateId: String,
        variantId: String,
        versionId: Int,
        updateDraftRequest: UpdateDraftRequest,
    ): ResponseEntity<VersionDto> {
        val templateModel = updateDraftRequest.templateModel?.let {
            objectMapper.treeToValue(objectMapper.valueToTree(it), app.epistola.suite.templates.model.TemplateDocument::class.java)
        } ?: throw ValidationException(field = "templateModel", message = "Template model is required")
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val variantIdComposite = VariantId(VariantKey.of(variantId), templateIdComposite)
        val versionIdComposite = VersionId(VersionKey.of(versionId), variantIdComposite)
        val version = UpdateVersion(
            versionId = versionIdComposite,
            templateModel = templateModel,
        ).execute()
        return ResponseEntity.ok(version.toDto(objectMapper))
    }

    override fun publishVersion(
        tenantId: String,
        catalogId: String,
        templateId: String,
        variantId: String,
        versionId: Int,
        publishVersionRequest: PublishVersionRequest,
    ): ResponseEntity<VersionDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val variantIdComposite = VariantId(VariantKey.of(variantId), templateIdComposite)
        val versionIdComposite = VersionId(VersionKey.of(versionId), variantIdComposite)
        val environmentIdComposite = EnvironmentId(EnvironmentKey.of(publishVersionRequest.environmentId), tenantIdComposite)
        val result = PublishToEnvironment(
            versionId = versionIdComposite,
            environmentId = environmentIdComposite,
        ).execute()
        return ResponseEntity.ok(result.version.toDto(objectMapper))
    }

    override fun archiveVersion(
        tenantId: String,
        catalogId: String,
        templateId: String,
        variantId: String,
        versionId: Int,
    ): ResponseEntity<VersionDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val variantIdComposite = VariantId(VariantKey.of(variantId), templateIdComposite)
        val versionIdComposite = VersionId(VersionKey.of(versionId), variantIdComposite)
        val archived = ArchiveVersion(
            versionId = versionIdComposite,
        ).execute()
        return ResponseEntity.ok(archived.toDto(objectMapper))
    }

    // ================== Helper methods ==================

    private fun getVariantSummary(variant: TemplateVariant, tenantId: TenantKey, catalogId: String): VariantVersionInfo {
        val tenantIdComposite = TenantId(tenantId)
        val templateIdComposite = TemplateId(variant.templateKey, CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val variantIdComposite = VariantId(variant.id, templateIdComposite)
        val versions = ListVersions(variantId = variantIdComposite).query()
        val hasDraft = versions.any { it.status == VersionStatus.DRAFT }
        val publishedVersions = versions
            .filter { it.status == VersionStatus.PUBLISHED }
            .map { it.id.value }
            .sorted()
        return VariantVersionInfo(
            hasDraft = hasDraft,
            publishedVersions = publishedVersions,
        )
    }
}

private fun RecentUsageImpact.toDto(): RecentUsageImpactDto = RecentUsageImpactDto(
    applicable = applicable,
    windowDays = windowDays,
    sampledDocuments = sampledDocuments,
    distinctShapes = distinctShapes,
    failingShapes = failingShapes,
    failingDocuments = failingDocuments,
    capped = capped,
    fields = fields.map { RecentUsageFieldImpactDto(path = it.path, reason = it.reason, failingDocuments = it.failingDocuments) },
)

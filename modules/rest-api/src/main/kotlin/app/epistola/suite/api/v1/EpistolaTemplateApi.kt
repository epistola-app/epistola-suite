package app.epistola.suite.api.v1

import app.epistola.api.TemplatesApi
import app.epistola.api.VariantsApi
import app.epistola.api.VersionsApi
import app.epistola.api.model.ActivationListResponse
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
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
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
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.model.TemplateVariant
import app.epistola.suite.templates.model.VersionStatus
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
import app.epistola.suite.templates.validation.JsonSchemaValidator
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

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
    ): ResponseEntity<TemplateListResponse> {
        val templates = ListDocumentTemplates(tenantId = TenantId(TenantKey.of(tenantId)), searchTerm = q).query()
        return ResponseEntity.ok(
            TemplateListResponse(
                items = templates.map { it.toSummaryDto() },
            ),
        )
    }

    override fun createTemplate(
        tenantId: String,
        catalogId: String,
        createTemplateRequest: CreateTemplateRequest,
    ): ResponseEntity<TemplateDto> {
        val schemaJson = createTemplateRequest.schema?.let { objectMapper.writeValueAsString(it) }
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val template = CreateDocumentTemplate(
            id = TemplateId(TemplateKey.of(createTemplateRequest.id), CatalogId(CatalogKey.of(catalogId), tenantIdComposite)),
            name = createTemplateRequest.name,
            schema = schemaJson,
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
            ?: return ResponseEntity.notFound().build()
        val variantSummaries = GetVariantSummaries(templateId = templateIdComposite).query()
        return ResponseEntity.ok(template.toDto(objectMapper, variantSummaries))
    }

    override fun updateTemplate(
        tenantId: String,
        catalogId: String,
        templateId: String,
        updateTemplateRequest: UpdateTemplateRequest,
    ): ResponseEntity<TemplateDto> {
        val dataExamples = updateTemplateRequest.dataExamples?.map {
            DataExample(id = it.id, name = it.name, data = it.data)
        }
        val dataModel = updateTemplateRequest.dataModel
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val result = UpdateDocumentTemplate(
            id = templateIdComposite,
            name = updateTemplateRequest.name,
            dataModel = dataModel,
            dataExamples = dataExamples,
            forceUpdate = updateTemplateRequest.forceUpdate ?: false,
        ).execute() ?: return ResponseEntity.notFound().build()
        val variantSummaries = GetVariantSummaries(templateId = templateIdComposite).query()
        return ResponseEntity.ok(result.template.toDto(objectMapper, variantSummaries))
    }

    override fun validateTemplateData(
        tenantId: String,
        catalogId: String,
        templateId: String,
        validateTemplateDataRequest: ValidateTemplateDataRequest,
    ): ResponseEntity<TemplateDataValidationResult> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val template = GetDocumentTemplate(id = templateIdComposite).query()
            ?: return ResponseEntity.notFound().build()

        val dataModel = template.publishedDataModel
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
            ResponseEntity.notFound().build()
        }
    }

    // ================== Variant operations ==================

    override fun listVariants(
        tenantId: String,
        catalogId: String,
        templateId: String,
    ): ResponseEntity<VariantListResponse> {
        val typedTenantId = TenantKey.of(tenantId)
        val tenantIdComposite = TenantId(typedTenantId)
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val variants = ListVariants(templateId = templateIdComposite).query()
        val variantDtos = variants.map { variant ->
            val summary = getVariantSummary(variant, typedTenantId, catalogId)
            variant.toDto(summary)
        }
        return ResponseEntity.ok(VariantListResponse(items = variantDtos))
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
        ).execute() ?: return ResponseEntity.notFound().build()
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
        val variant = GetVariant(variantId = variantIdComposite).query() ?: return ResponseEntity.notFound().build()
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
            ?: return ResponseEntity.badRequest().build()
        val typedTenantId = TenantKey.of(tenantId)
        val tenantIdComposite = TenantId(typedTenantId)
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val variantIdComposite = VariantId(VariantKey.of(variantId), templateIdComposite)
        val variant = UpdateVariant(
            variantId = variantIdComposite,
            title = updateVariantRequest.title,
            attributes = attributes,
        ).execute() ?: return ResponseEntity.notFound().build()
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
            ResponseEntity.notFound().build()
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
        ).execute() ?: return ResponseEntity.notFound().build()
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
        val draft = GetDraft(variantId = variantIdComposite).query() ?: return ResponseEntity.notFound().build()
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
        } ?: return ResponseEntity.badRequest().build()
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val variantIdComposite = VariantId(VariantKey.of(variantId), templateIdComposite)
        val draft = UpdateDraft(
            variantId = variantIdComposite,
            templateModel = templateModel,
        ).execute() ?: return ResponseEntity.notFound().build()
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
        val removed = RemoveActivation(
            variantId = variantIdComposite,
            environmentId = environmentIdComposite,
        ).execute()
        return if (removed) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
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
        val version = GetActiveVersion(variantId = variantIdComposite, environmentId = environmentIdComposite).query() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(version.toDto(objectMapper))
    }

    // ================== Version operations ==================

    override fun listVersions(
        tenantId: String,
        catalogId: String,
        templateId: String,
        variantId: String,
        status: String?,
    ): ResponseEntity<VersionListResponse> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val variantIdComposite = VariantId(VariantKey.of(variantId), templateIdComposite)
        val versions = ListVersions(variantId = variantIdComposite).query()
        val filteredVersions = if (status != null) {
            versions.filter { it.status.name.equals(status, ignoreCase = true) }
        } else {
            versions
        }
        return ResponseEntity.ok(
            VersionListResponse(
                items = filteredVersions.map { it.toSummaryDto() },
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
        val version = GetVersion(versionId = versionIdComposite).query() ?: return ResponseEntity.notFound().build()
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
        } ?: return ResponseEntity.badRequest().build()
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val templateIdComposite = TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val variantIdComposite = VariantId(VariantKey.of(variantId), templateIdComposite)
        val versionIdComposite = VersionId(VersionKey.of(versionId), variantIdComposite)
        val version = UpdateVersion(
            versionId = versionIdComposite,
            templateModel = templateModel,
        ).execute() ?: return ResponseEntity.notFound().build()
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
        ).execute() ?: return ResponseEntity.notFound().build()
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
        ).execute() ?: return ResponseEntity.notFound().build()
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

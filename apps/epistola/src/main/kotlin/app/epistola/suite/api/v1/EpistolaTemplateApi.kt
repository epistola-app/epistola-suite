package app.epistola.suite.api.v1

import app.epistola.api.TemplatesApi
import app.epistola.api.VariantsApi
import app.epistola.api.VersionsApi
import app.epistola.api.model.ActivationDto
import app.epistola.api.model.ActivationListResponse
import app.epistola.api.model.CreateTemplateRequest
import app.epistola.api.model.CreateVariantRequest
import app.epistola.api.model.SetActivationRequest
import app.epistola.api.model.TemplateDto
import app.epistola.api.model.TemplateListResponse
import app.epistola.api.model.UpdateDraftRequest
import app.epistola.api.model.UpdateTemplateRequest
import app.epistola.api.model.UpdateVariantRequest
import app.epistola.api.model.VariantDto
import app.epistola.api.model.VariantListResponse
import app.epistola.api.model.VersionDto
import app.epistola.api.model.VersionListResponse
import app.epistola.suite.api.v1.shared.TemplateModelHelper
import app.epistola.suite.api.v1.shared.VariantVersionInfo
import app.epistola.suite.api.v1.shared.toDto
import app.epistola.suite.api.v1.shared.toSummaryDto
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.DeleteDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.activations.RemoveActivation
import app.epistola.suite.templates.commands.activations.SetActivation
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.DeleteVariant
import app.epistola.suite.templates.commands.variants.UpdateVariant
import app.epistola.suite.templates.commands.versions.ArchiveVersion
import app.epistola.suite.templates.commands.versions.PublishVersion
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
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

@RestController
class EpistolaTemplateApi(
    private val objectMapper: ObjectMapper,
) : TemplatesApi,
    VariantsApi,
    VersionsApi {

    // ================== Template operations ==================

    override fun listTemplates(
        tenantId: String,
        q: String?,
    ): ResponseEntity<TemplateListResponse> {
        val templates = ListDocumentTemplates(tenantId = TenantId.of(tenantId), searchTerm = q).query()
        return ResponseEntity.ok(
            TemplateListResponse(
                items = templates.map { it.toSummaryDto() },
            ),
        )
    }

    override fun createTemplate(
        tenantId: String,
        createTemplateRequest: CreateTemplateRequest,
    ): ResponseEntity<TemplateDto> {
        val schemaJson = createTemplateRequest.schema?.let { objectMapper.writeValueAsString(it) }
        val template = CreateDocumentTemplate(
            id = TemplateId.of(createTemplateRequest.id),
            tenantId = TenantId.of(tenantId),
            name = createTemplateRequest.name,
            schema = schemaJson,
        ).execute()
        val variantSummaries = GetVariantSummaries(templateId = template.id).query()
        return ResponseEntity.status(HttpStatus.CREATED).body(template.toDto(objectMapper, variantSummaries))
    }

    override fun getTemplate(
        tenantId: String,
        templateId: String,
    ): ResponseEntity<TemplateDto> {
        val template = GetDocumentTemplate(tenantId = TenantId.of(tenantId), id = TemplateId.of(templateId)).query()
            ?: return ResponseEntity.notFound().build()
        val variantSummaries = GetVariantSummaries(templateId = TemplateId.of(templateId)).query()
        return ResponseEntity.ok(template.toDto(objectMapper, variantSummaries))
    }

    override fun updateTemplate(
        tenantId: String,
        templateId: String,
        updateTemplateRequest: UpdateTemplateRequest,
    ): ResponseEntity<TemplateDto> {
        val dataExamples = updateTemplateRequest.dataExamples?.map {
            DataExample(id = it.id, name = it.name, data = objectMapper.valueToTree(it.data))
        }
        val dataModel = updateTemplateRequest.dataModel?.let {
            objectMapper.valueToTree<ObjectNode>(it)
        }
        val result = UpdateDocumentTemplate(
            tenantId = TenantId.of(tenantId),
            id = TemplateId.of(templateId),
            name = updateTemplateRequest.name,
            dataModel = dataModel,
            dataExamples = dataExamples,
            forceUpdate = updateTemplateRequest.forceUpdate ?: false,
        ).execute() ?: return ResponseEntity.notFound().build()
        val variantSummaries = GetVariantSummaries(templateId = TemplateId.of(templateId)).query()
        return ResponseEntity.ok(result.template.toDto(objectMapper, variantSummaries))
    }

    override fun deleteTemplate(
        tenantId: String,
        templateId: String,
    ): ResponseEntity<Unit> {
        val deleted = DeleteDocumentTemplate(tenantId = TenantId.of(tenantId), id = TemplateId.of(templateId)).execute()
        return if (deleted) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

    // ================== Variant operations ==================

    override fun listVariants(
        tenantId: String,
        templateId: String,
    ): ResponseEntity<VariantListResponse> {
        val typedTenantId = TenantId.of(tenantId)
        val variants = ListVariants(tenantId = typedTenantId, templateId = TemplateId.of(templateId)).query()
        val variantDtos = variants.map { variant ->
            val summary = getVariantSummary(variant, typedTenantId)
            variant.toDto(summary)
        }
        return ResponseEntity.ok(VariantListResponse(items = variantDtos))
    }

    override fun createVariant(
        tenantId: String,
        templateId: String,
        createVariantRequest: CreateVariantRequest,
    ): ResponseEntity<VariantDto> {
        val typedTenantId = TenantId.of(tenantId)
        val variant = CreateVariant(
            id = VariantId.of(createVariantRequest.id),
            tenantId = typedTenantId,
            templateId = TemplateId.of(templateId),
            title = createVariantRequest.title,
            description = createVariantRequest.description,
            tags = createVariantRequest.tags ?: emptyMap(),
        ).execute() ?: return ResponseEntity.notFound().build()
        val summary = getVariantSummary(variant, typedTenantId)
        return ResponseEntity.status(HttpStatus.CREATED).body(variant.toDto(summary))
    }

    override fun getVariant(
        tenantId: String,
        templateId: String,
        variantId: String,
    ): ResponseEntity<VariantDto> {
        val typedTenantId = TenantId.of(tenantId)
        val variant = GetVariant(
            tenantId = typedTenantId,
            templateId = TemplateId.of(templateId),
            variantId = VariantId.of(variantId),
        ).query() ?: return ResponseEntity.notFound().build()
        val summary = getVariantSummary(variant, typedTenantId)
        return ResponseEntity.ok(variant.toDto(summary))
    }

    override fun updateVariant(
        tenantId: String,
        templateId: String,
        variantId: String,
        updateVariantRequest: UpdateVariantRequest,
    ): ResponseEntity<VariantDto> {
        val tags = updateVariantRequest.tags
            ?: return ResponseEntity.badRequest().build()
        val typedTenantId = TenantId.of(tenantId)
        val variant = UpdateVariant(
            tenantId = typedTenantId,
            templateId = TemplateId.of(templateId),
            variantId = VariantId.of(variantId),
            tags = tags,
        ).execute() ?: return ResponseEntity.notFound().build()
        val summary = getVariantSummary(variant, typedTenantId)
        return ResponseEntity.ok(variant.toDto(summary))
    }

    override fun deleteVariant(
        tenantId: String,
        templateId: String,
        variantId: String,
    ): ResponseEntity<Unit> {
        val deleted = DeleteVariant(
            tenantId = TenantId.of(tenantId),
            templateId = TemplateId.of(templateId),
            variantId = VariantId.of(variantId),
        ).execute()
        return if (deleted) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

    // ================== Draft operations ==================

    override fun getVariantDraft(
        tenantId: String,
        templateId: String,
        variantId: String,
    ): ResponseEntity<VersionDto> {
        val draft = GetDraft(
            tenantId = TenantId.of(tenantId),
            templateId = TemplateId.of(templateId),
            variantId = VariantId.of(variantId),
        ).query() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(draft.toDto(objectMapper))
    }

    override fun upsertVariantDraft(
        tenantId: String,
        templateId: String,
        variantId: String,
        updateDraftRequest: UpdateDraftRequest,
    ): ResponseEntity<VersionDto> {
        val templateModel = updateDraftRequest.templateModel?.let { TemplateModelHelper.parseTemplateModel(objectMapper, it) }
            ?: return ResponseEntity.badRequest().build()
        val draft = UpdateDraft(
            tenantId = TenantId.of(tenantId),
            templateId = TemplateId.of(templateId),
            variantId = VariantId.of(variantId),
            templateModel = templateModel,
        ).execute() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(draft.toDto(objectMapper))
    }

    // ================== Activation operations ==================

    override fun listVariantActivations(
        tenantId: String,
        templateId: String,
        variantId: String,
    ): ResponseEntity<ActivationListResponse> {
        val activations = ListActivations(
            tenantId = TenantId.of(tenantId),
            templateId = TemplateId.of(templateId),
            variantId = VariantId.of(variantId),
        ).query()
        return ResponseEntity.ok(ActivationListResponse(items = activations.map { it.toDto() }))
    }

    override fun setVariantActivation(
        tenantId: String,
        templateId: String,
        variantId: String,
        environmentId: UUID,
        setActivationRequest: SetActivationRequest,
    ): ResponseEntity<ActivationDto> {
        val activation = SetActivation(
            tenantId = TenantId.of(tenantId),
            templateId = TemplateId.of(templateId),
            variantId = VariantId.of(variantId),
            environmentId = EnvironmentId.of(environmentId),
            versionId = VersionId.of(setActivationRequest.versionId),
        ).execute() ?: return ResponseEntity.notFound().build()

        // Fetch full activation details for response
        val activations = ListActivations(
            tenantId = TenantId.of(tenantId),
            templateId = TemplateId.of(templateId),
            variantId = VariantId.of(variantId),
        ).query()
        val typedEnvId = EnvironmentId.of(environmentId)
        val activationDetails = activations.find { it.environmentId == typedEnvId }
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(activationDetails.toDto())
    }

    override fun removeVariantActivation(
        tenantId: String,
        templateId: String,
        variantId: String,
        environmentId: UUID,
    ): ResponseEntity<Unit> {
        val removed = RemoveActivation(
            tenantId = TenantId.of(tenantId),
            templateId = TemplateId.of(templateId),
            variantId = VariantId.of(variantId),
            environmentId = EnvironmentId.of(environmentId),
        ).execute()
        return if (removed) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

    override fun getActiveVersion(
        environment: UUID,
        tenantId: String,
        templateId: String,
        variantId: String,
    ): ResponseEntity<VersionDto> {
        val version = GetActiveVersion(
            tenantId = TenantId.of(tenantId),
            templateId = TemplateId.of(templateId),
            variantId = VariantId.of(variantId),
            environmentId = EnvironmentId.of(environment),
        ).query() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(version.toDto(objectMapper))
    }

    // ================== Version operations ==================

    override fun listVersions(
        tenantId: String,
        templateId: String,
        variantId: String,
        status: String?,
    ): ResponseEntity<VersionListResponse> {
        val versions = ListVersions(
            tenantId = TenantId.of(tenantId),
            templateId = TemplateId.of(templateId),
            variantId = VariantId.of(variantId),
        ).query()
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
        templateId: String,
        variantId: String,
        versionId: UUID,
    ): ResponseEntity<VersionDto> {
        val version = GetVersion(
            tenantId = TenantId.of(tenantId),
            templateId = TemplateId.of(templateId),
            variantId = VariantId.of(variantId),
            versionId = VersionId.of(versionId),
        ).query() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(version.toDto(objectMapper))
    }

    override fun updateVersion(
        tenantId: String,
        templateId: String,
        variantId: String,
        versionId: UUID,
        updateDraftRequest: UpdateDraftRequest,
    ): ResponseEntity<VersionDto> {
        val templateModel = updateDraftRequest.templateModel?.let { TemplateModelHelper.parseTemplateModel(objectMapper, it) }
            ?: return ResponseEntity.badRequest().build()
        val version = UpdateVersion(
            tenantId = TenantId.of(tenantId),
            templateId = TemplateId.of(templateId),
            variantId = VariantId.of(variantId),
            versionId = VersionId.of(versionId),
            templateModel = templateModel,
        ).execute() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(version.toDto(objectMapper))
    }

    override fun publishVersion(
        tenantId: String,
        templateId: String,
        variantId: String,
        versionId: UUID,
    ): ResponseEntity<VersionDto> {
        val published = PublishVersion(
            tenantId = TenantId.of(tenantId),
            templateId = TemplateId.of(templateId),
            variantId = VariantId.of(variantId),
            versionId = VersionId.of(versionId),
        ).execute() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(published.toDto(objectMapper))
    }

    override fun archiveVersion(
        tenantId: String,
        templateId: String,
        variantId: String,
        versionId: UUID,
    ): ResponseEntity<VersionDto> {
        val archived = ArchiveVersion(
            tenantId = TenantId.of(tenantId),
            templateId = TemplateId.of(templateId),
            variantId = VariantId.of(variantId),
            versionId = VersionId.of(versionId),
        ).execute() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(archived.toDto(objectMapper))
    }

    // ================== Helper methods ==================

    private fun getVariantSummary(variant: TemplateVariant, tenantId: TenantId): VariantVersionInfo {
        val versions = ListVersions(
            tenantId = tenantId,
            templateId = variant.templateId,
            variantId = variant.id,
        ).query()
        val hasDraft = versions.any { it.status == VersionStatus.DRAFT }
        val publishedVersions = versions
            .filter { it.status == VersionStatus.PUBLISHED }
            .mapNotNull { it.versionNumber }
            .sorted()
        return VariantVersionInfo(
            hasDraft = hasDraft,
            publishedVersions = publishedVersions,
        )
    }
}

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
import app.epistola.suite.activations.commands.RemoveActivation
import app.epistola.suite.activations.commands.SetActivation
import app.epistola.suite.activations.queries.GetActiveVersion
import app.epistola.suite.activations.queries.ListActivations
import app.epistola.suite.api.v1.shared.TemplateModelHelper
import app.epistola.suite.api.v1.shared.VariantVersionInfo
import app.epistola.suite.api.v1.shared.toDto
import app.epistola.suite.api.v1.shared.toSummaryDto
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.DeleteDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
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

@RestController
class EpistolaTemplateApi(
    private val mediator: Mediator,
    private val objectMapper: ObjectMapper,
) : TemplatesApi,
    VariantsApi,
    VersionsApi {

    // ================== Template operations ==================

    override fun listTemplates(
        tenantId: Long,
        q: String?,
    ): ResponseEntity<TemplateListResponse> {
        val templates = mediator.query(ListDocumentTemplates(tenantId = tenantId, searchTerm = q))
        return ResponseEntity.ok(
            TemplateListResponse(
                items = templates.map { it.toSummaryDto() },
            ),
        )
    }

    override fun createTemplate(
        tenantId: Long,
        createTemplateRequest: CreateTemplateRequest,
    ): ResponseEntity<TemplateDto> {
        val schemaJson = createTemplateRequest.schema?.let { objectMapper.writeValueAsString(it) }
        val template = mediator.send(
            CreateDocumentTemplate(
                tenantId = tenantId,
                name = createTemplateRequest.name,
                schema = schemaJson,
            ),
        )
        val variantSummaries = mediator.query(GetVariantSummaries(templateId = template.id))
        return ResponseEntity.status(HttpStatus.CREATED).body(template.toDto(objectMapper, variantSummaries))
    }

    override fun getTemplate(
        tenantId: Long,
        templateId: Long,
    ): ResponseEntity<TemplateDto> {
        val template = mediator.query(GetDocumentTemplate(tenantId = tenantId, id = templateId))
            ?: return ResponseEntity.notFound().build()
        val variantSummaries = mediator.query(GetVariantSummaries(templateId = templateId))
        return ResponseEntity.ok(template.toDto(objectMapper, variantSummaries))
    }

    override fun updateTemplate(
        tenantId: Long,
        templateId: Long,
        updateTemplateRequest: UpdateTemplateRequest,
    ): ResponseEntity<TemplateDto> {
        val dataExamples = updateTemplateRequest.dataExamples?.map {
            DataExample(id = it.id, name = it.name, data = objectMapper.valueToTree(it.data))
        }
        val dataModel = updateTemplateRequest.dataModel?.let {
            objectMapper.valueToTree<ObjectNode>(it)
        }
        val result = mediator.send(
            UpdateDocumentTemplate(
                tenantId = tenantId,
                id = templateId,
                name = updateTemplateRequest.name,
                dataModel = dataModel,
                dataExamples = dataExamples,
                forceUpdate = updateTemplateRequest.forceUpdate ?: false,
            ),
        ) ?: return ResponseEntity.notFound().build()
        val variantSummaries = mediator.query(GetVariantSummaries(templateId = templateId))
        return ResponseEntity.ok(result.template.toDto(objectMapper, variantSummaries))
    }

    override fun deleteTemplate(
        tenantId: Long,
        templateId: Long,
    ): ResponseEntity<Unit> {
        val deleted = mediator.send(DeleteDocumentTemplate(tenantId = tenantId, id = templateId))
        return if (deleted) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

    // ================== Variant operations ==================

    override fun listVariants(
        tenantId: Long,
        templateId: Long,
    ): ResponseEntity<VariantListResponse> {
        val variants = mediator.query(ListVariants(tenantId = tenantId, templateId = templateId))
        val variantDtos = variants.map { variant ->
            val summary = getVariantSummary(variant)
            variant.toDto(summary)
        }
        return ResponseEntity.ok(VariantListResponse(items = variantDtos))
    }

    override fun createVariant(
        tenantId: Long,
        templateId: Long,
        createVariantRequest: CreateVariantRequest,
    ): ResponseEntity<VariantDto> {
        val variant = mediator.send(
            CreateVariant(
                tenantId = tenantId,
                templateId = templateId,
                title = createVariantRequest.title,
                description = createVariantRequest.description,
                tags = createVariantRequest.tags ?: emptyMap(),
            ),
        ) ?: return ResponseEntity.notFound().build()
        val summary = getVariantSummary(variant)
        return ResponseEntity.status(HttpStatus.CREATED).body(variant.toDto(summary))
    }

    override fun getVariant(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
    ): ResponseEntity<VariantDto> {
        val variant = mediator.query(
            GetVariant(
                tenantId = tenantId,
                templateId = templateId,
                variantId = variantId,
            ),
        ) ?: return ResponseEntity.notFound().build()
        val summary = getVariantSummary(variant)
        return ResponseEntity.ok(variant.toDto(summary))
    }

    override fun updateVariant(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
        updateVariantRequest: UpdateVariantRequest,
    ): ResponseEntity<VariantDto> {
        val tags = updateVariantRequest.tags
            ?: return ResponseEntity.badRequest().build()
        val variant = mediator.send(
            UpdateVariant(
                tenantId = tenantId,
                templateId = templateId,
                variantId = variantId,
                tags = tags,
            ),
        ) ?: return ResponseEntity.notFound().build()
        val summary = getVariantSummary(variant)
        return ResponseEntity.ok(variant.toDto(summary))
    }

    override fun deleteVariant(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
    ): ResponseEntity<Unit> {
        val deleted = mediator.send(
            DeleteVariant(
                tenantId = tenantId,
                templateId = templateId,
                variantId = variantId,
            ),
        )
        return if (deleted) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

    // ================== Draft operations ==================

    override fun getVariantDraft(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
    ): ResponseEntity<VersionDto> {
        val draft = mediator.query(
            GetDraft(
                tenantId = tenantId,
                templateId = templateId,
                variantId = variantId,
            ),
        ) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(draft.toDto(objectMapper))
    }

    override fun upsertVariantDraft(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
        updateDraftRequest: UpdateDraftRequest,
    ): ResponseEntity<VersionDto> {
        val templateModel = updateDraftRequest.templateModel?.let { TemplateModelHelper.parseTemplateModel(objectMapper, it) }
        val draft = mediator.send(
            UpdateDraft(
                tenantId = tenantId,
                templateId = templateId,
                variantId = variantId,
                templateModel = templateModel,
            ),
        ) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(draft.toDto(objectMapper))
    }

    // ================== Activation operations ==================

    override fun listVariantActivations(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
    ): ResponseEntity<ActivationListResponse> {
        val activations = mediator.query(
            ListActivations(
                tenantId = tenantId,
                templateId = templateId,
                variantId = variantId,
            ),
        )
        return ResponseEntity.ok(ActivationListResponse(items = activations.map { it.toDto() }))
    }

    override fun setVariantActivation(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
        environmentId: Long,
        setActivationRequest: SetActivationRequest,
    ): ResponseEntity<ActivationDto> {
        val activation = mediator.send(
            SetActivation(
                tenantId = tenantId,
                templateId = templateId,
                variantId = variantId,
                environmentId = environmentId,
                versionId = setActivationRequest.versionId,
            ),
        ) ?: return ResponseEntity.notFound().build()

        // Fetch full activation details for response
        val activations = mediator.query(
            ListActivations(
                tenantId = tenantId,
                templateId = templateId,
                variantId = variantId,
            ),
        )
        val activationDetails = activations.find { it.environmentId == environmentId }
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(activationDetails.toDto())
    }

    override fun removeVariantActivation(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
        environmentId: Long,
    ): ResponseEntity<Unit> {
        val removed = mediator.send(
            RemoveActivation(
                tenantId = tenantId,
                templateId = templateId,
                variantId = variantId,
                environmentId = environmentId,
            ),
        )
        return if (removed) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

    override fun getActiveVersion(
        environment: Long,
        tenantId: Long,
        templateId: Long,
        variantId: Long,
    ): ResponseEntity<VersionDto> {
        val version = mediator.query(
            GetActiveVersion(
                tenantId = tenantId,
                templateId = templateId,
                variantId = variantId,
                environmentId = environment,
            ),
        ) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(version.toDto(objectMapper))
    }

    // ================== Version operations ==================

    override fun listVersions(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
        status: String?,
    ): ResponseEntity<VersionListResponse> {
        val versions = mediator.query(
            ListVersions(
                tenantId = tenantId,
                templateId = templateId,
                variantId = variantId,
            ),
        )
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
        tenantId: Long,
        templateId: Long,
        variantId: Long,
        versionId: Long,
    ): ResponseEntity<VersionDto> {
        val version = mediator.query(
            GetVersion(
                tenantId = tenantId,
                templateId = templateId,
                variantId = variantId,
                versionId = versionId,
            ),
        ) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(version.toDto(objectMapper))
    }

    override fun updateVersion(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
        versionId: Long,
        updateDraftRequest: UpdateDraftRequest,
    ): ResponseEntity<VersionDto> {
        val templateModel = updateDraftRequest.templateModel?.let { TemplateModelHelper.parseTemplateModel(objectMapper, it) }
        val version = mediator.send(
            UpdateVersion(
                tenantId = tenantId,
                templateId = templateId,
                variantId = variantId,
                versionId = versionId,
                templateModel = templateModel,
            ),
        ) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(version.toDto(objectMapper))
    }

    override fun publishVersion(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
        versionId: Long,
    ): ResponseEntity<VersionDto> {
        val published = mediator.send(
            PublishVersion(
                tenantId = tenantId,
                templateId = templateId,
                variantId = variantId,
                versionId = versionId,
            ),
        ) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(published.toDto(objectMapper))
    }

    override fun archiveVersion(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
        versionId: Long,
    ): ResponseEntity<VersionDto> {
        val archived = mediator.send(
            ArchiveVersion(
                tenantId = tenantId,
                templateId = templateId,
                variantId = variantId,
                versionId = versionId,
            ),
        ) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(archived.toDto(objectMapper))
    }

    // ================== Helper methods ==================

    private fun getVariantSummary(variant: TemplateVariant): VariantVersionInfo {
        val versions = mediator.query(
            ListVersions(
                tenantId = 0, // Not used for summary
                templateId = variant.templateId,
                variantId = variant.id,
            ),
        )
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

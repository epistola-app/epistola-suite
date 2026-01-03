package app.epistola.suite.api.v1

import app.epistola.api.V1Api
import app.epistola.api.model.ActivationDto
import app.epistola.api.model.ActivationListResponse
import app.epistola.api.model.CreateEnvironmentRequest
import app.epistola.api.model.CreateTemplateRequest
import app.epistola.api.model.CreateTenantRequest
import app.epistola.api.model.CreateVariantRequest
import app.epistola.api.model.DataExampleDto
import app.epistola.api.model.EnvironmentDto
import app.epistola.api.model.EnvironmentListResponse
import app.epistola.api.model.SetActivationRequest
import app.epistola.api.model.TemplateDto
import app.epistola.api.model.TemplateListResponse
import app.epistola.api.model.TemplateSummaryDto
import app.epistola.api.model.TenantDto
import app.epistola.api.model.TenantListResponse
import app.epistola.api.model.UpdateDraftRequest
import app.epistola.api.model.UpdateEnvironmentRequest
import app.epistola.api.model.UpdateTemplateRequest
import app.epistola.api.model.UpdateTenantRequest
import app.epistola.api.model.UpdateVariantRequest
import app.epistola.api.model.VariantDto
import app.epistola.api.model.VariantListResponse
import app.epistola.api.model.VariantSummaryDto
import app.epistola.api.model.VersionDto
import app.epistola.api.model.VersionListResponse
import app.epistola.api.model.VersionSummaryDto
import app.epistola.suite.activations.ActivationDetails
import app.epistola.suite.activations.commands.RemoveActivation
import app.epistola.suite.activations.commands.SetActivation
import app.epistola.suite.activations.queries.GetActiveVersion
import app.epistola.suite.activations.queries.ListActivations
import app.epistola.suite.environments.Environment
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeleteEnvironment
import app.epistola.suite.environments.commands.UpdateEnvironment
import app.epistola.suite.environments.queries.GetEnvironment
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.DeleteDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.model.TemplateModel
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.commands.DeleteTenant
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.tenants.queries.ListTenants
import app.epistola.suite.variants.TemplateVariant
import app.epistola.suite.variants.VariantSummary
import app.epistola.suite.variants.commands.CreateVariant
import app.epistola.suite.variants.commands.DeleteVariant
import app.epistola.suite.variants.commands.UpdateVariant
import app.epistola.suite.variants.queries.GetVariant
import app.epistola.suite.variants.queries.GetVariantSummaries
import app.epistola.suite.variants.queries.ListVariants
import app.epistola.suite.versions.TemplateVersion
import app.epistola.suite.versions.VersionStatus
import app.epistola.suite.versions.commands.ArchiveVersion
import app.epistola.suite.versions.commands.PublishVersion
import app.epistola.suite.versions.commands.UpdateDraft
import app.epistola.suite.versions.commands.UpdateVersion
import app.epistola.suite.versions.queries.GetDraft
import app.epistola.suite.versions.queries.GetVersion
import app.epistola.suite.versions.queries.ListVersions
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

@RestController
class V1ApiController(
    private val mediator: Mediator,
    private val objectMapper: ObjectMapper,
) : V1Api {

    // ================== Tenant operations ==================

    override fun listTenants(
        q: String?,
        page: Int,
        size: Int,
    ): ResponseEntity<TenantListResponse> {
        val tenants = mediator.query(ListTenants(searchTerm = q))

        val response = TenantListResponse(
            items = tenants.map { it.toDto() },
            page = page,
            propertySize = size,
            totalElements = tenants.size.toLong(),
            totalPages = 1,
        )

        return ResponseEntity.ok(response)
    }

    override fun createTenant(
        createTenantRequest: CreateTenantRequest,
    ): ResponseEntity<TenantDto> {
        val tenant = mediator.send(CreateTenant(name = createTenantRequest.name))

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(tenant.toDto())
    }

    override fun getTenant(
        tenantId: Long,
    ): ResponseEntity<TenantDto> {
        val tenant = mediator.query(GetTenant(id = tenantId))
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(tenant.toDto())
    }

    override fun updateTenant(
        tenantId: Long,
        updateTenantRequest: UpdateTenantRequest,
    ): ResponseEntity<TenantDto> {
        // TODO: Implement UpdateTenant command
        return ResponseEntity.notFound().build()
    }

    override fun deleteTenant(
        tenantId: Long,
    ): ResponseEntity<Unit> {
        val deleted = mediator.send(DeleteTenant(id = tenantId))
        return if (deleted) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

    // ================== Environment operations ==================

    override fun listEnvironments(
        tenantId: Long,
    ): ResponseEntity<EnvironmentListResponse> {
        val environments = mediator.query(ListEnvironments(tenantId = tenantId))
        return ResponseEntity.ok(EnvironmentListResponse(items = environments.map { it.toDto() }))
    }

    override fun createEnvironment(
        tenantId: Long,
        createEnvironmentRequest: CreateEnvironmentRequest,
    ): ResponseEntity<EnvironmentDto> {
        val environment = mediator.send(
            CreateEnvironment(
                tenantId = tenantId,
                name = createEnvironmentRequest.name,
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(environment.toDto())
    }

    override fun getEnvironment(
        tenantId: Long,
        environmentId: Long,
    ): ResponseEntity<EnvironmentDto> {
        val environment = mediator.query(GetEnvironment(tenantId = tenantId, id = environmentId))
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(environment.toDto())
    }

    override fun updateEnvironment(
        tenantId: Long,
        environmentId: Long,
        updateEnvironmentRequest: UpdateEnvironmentRequest,
    ): ResponseEntity<EnvironmentDto> {
        val name = updateEnvironmentRequest.name
            ?: return ResponseEntity.badRequest().build()
        val environment = mediator.send(
            UpdateEnvironment(
                tenantId = tenantId,
                id = environmentId,
                name = name,
            ),
        ) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(environment.toDto())
    }

    override fun deleteEnvironment(
        tenantId: Long,
        environmentId: Long,
    ): ResponseEntity<Unit> {
        val deleted = mediator.send(DeleteEnvironment(tenantId = tenantId, id = environmentId))
        return if (deleted) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

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
        return ResponseEntity.status(HttpStatus.CREATED).body(template.toDto(variantSummaries))
    }

    override fun getTemplate(
        tenantId: Long,
        templateId: Long,
    ): ResponseEntity<TemplateDto> {
        val template = mediator.query(GetDocumentTemplate(tenantId = tenantId, id = templateId))
            ?: return ResponseEntity.notFound().build()
        val variantSummaries = mediator.query(GetVariantSummaries(templateId = templateId))
        return ResponseEntity.ok(template.toDto(variantSummaries))
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
        val template = mediator.send(
            UpdateDocumentTemplate(
                tenantId = tenantId,
                id = templateId,
                name = updateTemplateRequest.name,
                dataModel = dataModel,
                dataExamples = dataExamples,
            ),
        ) ?: return ResponseEntity.notFound().build()
        val variantSummaries = mediator.query(GetVariantSummaries(templateId = templateId))
        return ResponseEntity.ok(template.toDto(variantSummaries))
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
        return ResponseEntity.ok(draft.toDto())
    }

    override fun upsertVariantDraft(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
        updateDraftRequest: UpdateDraftRequest,
    ): ResponseEntity<VersionDto> {
        val templateModel = updateDraftRequest.templateModel?.let { parseTemplateModel(it) }
        val draft = mediator.send(
            UpdateDraft(
                tenantId = tenantId,
                templateId = templateId,
                variantId = variantId,
                templateModel = templateModel,
            ),
        ) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(draft.toDto())
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
        return ResponseEntity.ok(version.toDto())
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
        return ResponseEntity.ok(version.toDto())
    }

    override fun updateVersion(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
        versionId: Long,
        updateDraftRequest: UpdateDraftRequest,
    ): ResponseEntity<VersionDto> {
        val templateModel = updateDraftRequest.templateModel?.let { parseTemplateModel(it) }
        val version = mediator.send(
            UpdateVersion(
                tenantId = tenantId,
                templateId = templateId,
                variantId = variantId,
                versionId = versionId,
                templateModel = templateModel,
            ),
        ) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(version.toDto())
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
        return ResponseEntity.ok(published.toDto())
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
        return ResponseEntity.ok(archived.toDto())
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

    private fun parseTemplateModel(map: Map<String, Any>): TemplateModel = objectMapper.convertValue(map, TemplateModel::class.java)

    // ================== Mappers ==================

    private fun Tenant.toDto() = TenantDto(
        id = id,
        name = name,
        createdAt = createdAt,
    )

    private fun Environment.toDto() = EnvironmentDto(
        id = id,
        tenantId = tenantId,
        name = name,
        createdAt = createdAt,
    )

    private fun DocumentTemplate.toSummaryDto() = TemplateSummaryDto(
        id = id,
        tenantId = tenantId,
        name = name,
        createdAt = createdAt,
        lastModified = lastModified,
    )

    private fun DocumentTemplate.toDto(variantSummaries: List<VariantSummary>) = TemplateDto(
        id = id,
        tenantId = tenantId,
        name = name,
        schema = schema?.let { objectMapper.convertValue(it, Map::class.java) as Map<String, Any> },
        dataModel = dataModel?.let { objectMapper.convertValue(it, Map::class.java) as Map<String, Any> },
        dataExamples = dataExamples.mapIndexed { index, example ->
            DataExampleDto(
                id = "$id-example-$index",
                name = example.name,
                data = objectMapper.convertValue(example.data, Map::class.java) as Map<String, Any>,
            )
        },
        variants = variantSummaries.map { it.toDto() },
        createdAt = createdAt,
        lastModified = lastModified,
    )

    private fun VariantSummary.toDto() = VariantSummaryDto(
        id = id,
        title = title,
        tags = tags,
        hasDraft = hasDraft,
        publishedVersions = publishedVersions,
    )

    private data class VariantVersionInfo(
        val hasDraft: Boolean,
        val publishedVersions: List<Int>,
    )

    private fun TemplateVariant.toDto(info: VariantVersionInfo) = VariantDto(
        id = id,
        templateId = templateId,
        title = title,
        description = description,
        tags = tags,
        hasDraft = info.hasDraft,
        publishedVersions = info.publishedVersions,
        createdAt = createdAt,
        lastModified = lastModified,
    )

    private fun TemplateVersion.toDto() = VersionDto(
        id = id,
        variantId = variantId,
        versionNumber = versionNumber,
        templateModel = templateModel?.let { objectMapper.convertValue(it, Map::class.java) as Map<String, Any> },
        status = status.toDtoStatus(),
        createdAt = createdAt,
        publishedAt = publishedAt,
        archivedAt = archivedAt,
    )

    private fun app.epistola.suite.versions.VersionSummary.toSummaryDto() = VersionSummaryDto(
        id = id,
        variantId = variantId,
        versionNumber = versionNumber,
        status = status.toSummaryDtoStatus(),
        createdAt = createdAt,
        publishedAt = publishedAt,
        archivedAt = archivedAt,
    )

    private fun VersionStatus.toDtoStatus() = when (this) {
        VersionStatus.DRAFT -> VersionDto.Status.DRAFT
        VersionStatus.PUBLISHED -> VersionDto.Status.PUBLISHED
        VersionStatus.ARCHIVED -> VersionDto.Status.ARCHIVED
    }

    private fun VersionStatus.toSummaryDtoStatus() = when (this) {
        VersionStatus.DRAFT -> VersionSummaryDto.Status.DRAFT
        VersionStatus.PUBLISHED -> VersionSummaryDto.Status.PUBLISHED
        VersionStatus.ARCHIVED -> VersionSummaryDto.Status.ARCHIVED
    }

    private fun ActivationDetails.toDto() = ActivationDto(
        environmentId = environmentId,
        environmentName = environmentName,
        versionId = versionId,
        versionNumber = versionNumber,
        activatedAt = activatedAt,
    )
}

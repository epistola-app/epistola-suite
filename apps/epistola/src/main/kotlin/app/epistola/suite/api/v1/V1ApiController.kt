package app.epistola.suite.api.v1

import app.epistola.api.V1Api
import app.epistola.api.model.ActivationDto
import app.epistola.api.model.ActivationListResponse
import app.epistola.api.model.CreateEnvironmentRequest
import app.epistola.api.model.CreateTemplateRequest
import app.epistola.api.model.CreateTenantRequest
import app.epistola.api.model.CreateVariantRequest
import app.epistola.api.model.EnvironmentDto
import app.epistola.api.model.EnvironmentListResponse
import app.epistola.api.model.SetActivationRequest
import app.epistola.api.model.TemplateDto
import app.epistola.api.model.TemplateListResponse
import app.epistola.api.model.TenantDto
import app.epistola.api.model.TenantListResponse
import app.epistola.api.model.UpdateDraftRequest
import app.epistola.api.model.UpdateEnvironmentRequest
import app.epistola.api.model.UpdateTemplateRequest
import app.epistola.api.model.UpdateTenantRequest
import app.epistola.api.model.UpdateVariantRequest
import app.epistola.api.model.VariantDto
import app.epistola.api.model.VariantListResponse
import app.epistola.api.model.VersionDto
import app.epistola.api.model.VersionListResponse
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.queries.ListTenants
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class V1ApiController(
    private val mediator: Mediator,
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
        // TODO: Implement GetTenant query
        return ResponseEntity.notFound().build()
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
        // TODO: Implement DeleteTenant command
        return ResponseEntity.noContent().build()
    }

    // ================== Environment operations ==================

    override fun listEnvironments(
        tenantId: Long,
    ): ResponseEntity<EnvironmentListResponse> {
        // TODO: Implement ListEnvironments query
        return ResponseEntity.ok(EnvironmentListResponse(items = emptyList()))
    }

    override fun createEnvironment(
        tenantId: Long,
        createEnvironmentRequest: CreateEnvironmentRequest,
    ): ResponseEntity<EnvironmentDto> {
        // TODO: Implement CreateEnvironment command
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build()
    }

    override fun getEnvironment(
        tenantId: Long,
        environmentId: Long,
    ): ResponseEntity<EnvironmentDto> {
        // TODO: Implement GetEnvironment query
        return ResponseEntity.notFound().build()
    }

    override fun updateEnvironment(
        tenantId: Long,
        environmentId: Long,
        updateEnvironmentRequest: UpdateEnvironmentRequest,
    ): ResponseEntity<EnvironmentDto> {
        // TODO: Implement UpdateEnvironment command
        return ResponseEntity.notFound().build()
    }

    override fun deleteEnvironment(
        tenantId: Long,
        environmentId: Long,
    ): ResponseEntity<Unit> {
        // TODO: Implement DeleteEnvironment command
        return ResponseEntity.noContent().build()
    }

    // ================== Template operations ==================

    override fun listTemplates(
        tenantId: Long,
        q: String?,
    ): ResponseEntity<TemplateListResponse> {
        // TODO: Implement using existing ListDocumentTemplates query
        return ResponseEntity.notFound().build()
    }

    override fun createTemplate(
        tenantId: Long,
        createTemplateRequest: CreateTemplateRequest,
    ): ResponseEntity<TemplateDto> {
        // TODO: Implement using existing CreateDocumentTemplate command
        return ResponseEntity.notFound().build()
    }

    override fun getTemplate(
        tenantId: Long,
        templateId: Long,
    ): ResponseEntity<TemplateDto> {
        // TODO: Implement using existing GetDocumentTemplate query
        return ResponseEntity.notFound().build()
    }

    override fun updateTemplate(
        tenantId: Long,
        templateId: Long,
        updateTemplateRequest: UpdateTemplateRequest,
    ): ResponseEntity<TemplateDto> {
        // TODO: Implement using existing UpdateDocumentTemplate command
        return ResponseEntity.notFound().build()
    }

    override fun deleteTemplate(
        tenantId: Long,
        templateId: Long,
    ): ResponseEntity<Unit> {
        // TODO: Implement DeleteDocumentTemplate command
        return ResponseEntity.noContent().build()
    }

    // ================== Variant operations ==================

    override fun listVariants(
        tenantId: Long,
        templateId: Long,
    ): ResponseEntity<VariantListResponse> {
        // TODO: Implement ListVariants query
        return ResponseEntity.ok(VariantListResponse(items = emptyList()))
    }

    override fun createVariant(
        tenantId: Long,
        templateId: Long,
        createVariantRequest: CreateVariantRequest,
    ): ResponseEntity<VariantDto> {
        // TODO: Implement CreateVariant command
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build()
    }

    override fun getVariant(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
    ): ResponseEntity<VariantDto> {
        // TODO: Implement GetVariant query
        return ResponseEntity.notFound().build()
    }

    override fun updateVariant(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
        updateVariantRequest: UpdateVariantRequest,
    ): ResponseEntity<VariantDto> {
        // TODO: Implement UpdateVariant command
        return ResponseEntity.notFound().build()
    }

    override fun deleteVariant(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
    ): ResponseEntity<Unit> {
        // TODO: Implement DeleteVariant command
        return ResponseEntity.noContent().build()
    }

    // ================== Draft operations ==================

    override fun getVariantDraft(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
    ): ResponseEntity<VersionDto> {
        // TODO: Implement GetVariantDraft query
        return ResponseEntity.notFound().build()
    }

    override fun upsertVariantDraft(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
        updateDraftRequest: UpdateDraftRequest,
    ): ResponseEntity<VersionDto> {
        // TODO: Implement UpsertVariantDraft command
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build()
    }

    // ================== Activation operations ==================

    override fun listVariantActivations(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
    ): ResponseEntity<ActivationListResponse> {
        // TODO: Implement ListVariantActivations query
        return ResponseEntity.ok(ActivationListResponse(items = emptyList()))
    }

    override fun setVariantActivation(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
        environmentId: Long,
        setActivationRequest: SetActivationRequest,
    ): ResponseEntity<ActivationDto> {
        // TODO: Implement SetVariantActivation command
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build()
    }

    override fun removeVariantActivation(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
        environmentId: Long,
    ): ResponseEntity<Unit> {
        // TODO: Implement RemoveVariantActivation command
        return ResponseEntity.noContent().build()
    }

    override fun getActiveVersion(
        environment: Long,
        tenantId: Long,
        templateId: Long,
        variantId: Long,
    ): ResponseEntity<VersionDto> {
        // TODO: Implement GetActiveVersion query
        return ResponseEntity.notFound().build()
    }

    // ================== Version operations ==================

    override fun listVersions(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
        status: String?,
    ): ResponseEntity<VersionListResponse> {
        // TODO: Implement ListVersions query
        return ResponseEntity.ok(VersionListResponse(items = emptyList()))
    }

    override fun getVersion(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
        versionId: Long,
    ): ResponseEntity<VersionDto> {
        // TODO: Implement GetVersion query
        return ResponseEntity.notFound().build()
    }

    override fun updateVersion(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
        versionId: Long,
        updateDraftRequest: UpdateDraftRequest,
    ): ResponseEntity<VersionDto> {
        // TODO: Implement UpdateVersion command (only for drafts)
        return ResponseEntity.notFound().build()
    }

    override fun publishVersion(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
        versionId: Long,
    ): ResponseEntity<VersionDto> {
        // TODO: Implement PublishVersion command
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build()
    }

    override fun archiveVersion(
        tenantId: Long,
        templateId: Long,
        variantId: Long,
        versionId: Long,
    ): ResponseEntity<VersionDto> {
        // TODO: Implement ArchiveVersion command
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build()
    }

    // ================== Mappers ==================

    private fun Tenant.toDto() = TenantDto(
        id = id,
        name = name,
        createdAt = createdAt,
    )
}

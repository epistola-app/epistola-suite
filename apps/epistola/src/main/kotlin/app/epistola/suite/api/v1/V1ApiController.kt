package app.epistola.suite.api.v1

import app.epistola.api.V1Api
import app.epistola.api.model.CreateTemplateRequest
import app.epistola.api.model.CreateTenantRequest
import app.epistola.api.model.TemplateDto
import app.epistola.api.model.TemplateListResponse
import app.epistola.api.model.TenantDto
import app.epistola.api.model.TenantListResponse
import app.epistola.api.model.UpdateTemplateRequest
import app.epistola.api.model.UpdateTenantRequest
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

    // Tenant operations

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

    // Template operations (not yet implemented)

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

    // Mappers

    private fun Tenant.toDto() = TenantDto(
        id = id,
        name = name,
        createdAt = createdAt,
    )
}

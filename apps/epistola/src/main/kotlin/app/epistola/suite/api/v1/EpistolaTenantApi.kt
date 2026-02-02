package app.epistola.suite.api.v1

import app.epistola.api.EnvironmentsApi
import app.epistola.api.TenantsApi
import app.epistola.api.model.CreateEnvironmentRequest
import app.epistola.api.model.CreateTenantRequest
import app.epistola.api.model.EnvironmentDto
import app.epistola.api.model.EnvironmentListResponse
import app.epistola.api.model.TenantDto
import app.epistola.api.model.TenantListResponse
import app.epistola.api.model.UpdateEnvironmentRequest
import app.epistola.api.model.UpdateTenantRequest
import app.epistola.suite.api.v1.shared.toDto
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeleteEnvironment
import app.epistola.suite.environments.commands.UpdateEnvironment
import app.epistola.suite.environments.queries.GetEnvironment
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.commands.DeleteTenant
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.tenants.queries.ListTenants
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class EpistolaTenantApi :
    TenantsApi,
    EnvironmentsApi {

    // ================== Tenant operations ==================

    override fun listTenants(
        q: String?,
        page: Int,
        size: Int,
    ): ResponseEntity<TenantListResponse> {
        val tenants = ListTenants(searchTerm = q).query()

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
        val tenant = CreateTenant(
            id = TenantId.of(createTenantRequest.id),
            name = createTenantRequest.name,
        ).execute()

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(tenant.toDto())
    }

    override fun getTenant(
        tenantId: String,
    ): ResponseEntity<TenantDto> {
        val tenant = GetTenant(id = TenantId.of(tenantId)).query()
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(tenant.toDto())
    }

    override fun updateTenant(
        tenantId: String,
        updateTenantRequest: UpdateTenantRequest,
    ): ResponseEntity<TenantDto> {
        // TODO: Implement UpdateTenant command
        return ResponseEntity.notFound().build()
    }

    override fun deleteTenant(
        tenantId: String,
    ): ResponseEntity<Unit> {
        val deleted = DeleteTenant(id = TenantId.of(tenantId)).execute()
        return if (deleted) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

    // ================== Environment operations ==================

    override fun listEnvironments(
        tenantId: String,
    ): ResponseEntity<EnvironmentListResponse> {
        val environments = ListEnvironments(tenantId = TenantId.of(tenantId)).query()
        return ResponseEntity.ok(EnvironmentListResponse(items = environments.map { it.toDto() }))
    }

    override fun createEnvironment(
        tenantId: String,
        createEnvironmentRequest: CreateEnvironmentRequest,
    ): ResponseEntity<EnvironmentDto> {
        val environment = CreateEnvironment(
            id = EnvironmentId.of(createEnvironmentRequest.id),
            tenantId = TenantId.of(tenantId),
            name = createEnvironmentRequest.name,
        ).execute()
        return ResponseEntity.status(HttpStatus.CREATED).body(environment.toDto())
    }

    override fun getEnvironment(
        tenantId: String,
        environmentId: UUID,
    ): ResponseEntity<EnvironmentDto> {
        val environment = GetEnvironment(tenantId = TenantId.of(tenantId), id = EnvironmentId.of(environmentId)).query()
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(environment.toDto())
    }

    override fun updateEnvironment(
        tenantId: String,
        environmentId: UUID,
        updateEnvironmentRequest: UpdateEnvironmentRequest,
    ): ResponseEntity<EnvironmentDto> {
        val name = updateEnvironmentRequest.name
            ?: return ResponseEntity.badRequest().build()
        val environment = UpdateEnvironment(
            tenantId = TenantId.of(tenantId),
            id = EnvironmentId.of(environmentId),
            name = name,
        ).execute() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(environment.toDto())
    }

    override fun deleteEnvironment(
        tenantId: String,
        environmentId: UUID,
    ): ResponseEntity<Unit> {
        val deleted = DeleteEnvironment(tenantId = TenantId.of(tenantId), id = EnvironmentId.of(environmentId)).execute()
        return if (deleted) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }
}

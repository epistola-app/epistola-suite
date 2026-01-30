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
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeleteEnvironment
import app.epistola.suite.environments.commands.UpdateEnvironment
import app.epistola.suite.environments.queries.GetEnvironment
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.commands.DeleteTenant
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.tenants.queries.ListTenants
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class EpistolaTenantApi(
    private val mediator: Mediator,
) : TenantsApi,
    EnvironmentsApi {

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
}

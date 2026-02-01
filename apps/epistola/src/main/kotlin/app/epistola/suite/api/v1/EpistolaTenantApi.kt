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
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.commands.DeleteTenant
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.tenants.queries.ListTenants
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

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
        val tenant = mediator.send(
            CreateTenant(
                id = TenantId.of(createTenantRequest.id),
                name = createTenantRequest.name,
            ),
        )

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(tenant.toDto())
    }

    override fun getTenant(
        tenantId: UUID,
    ): ResponseEntity<TenantDto> {
        val tenant = mediator.query(GetTenant(id = TenantId.of(tenantId)))
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(tenant.toDto())
    }

    override fun updateTenant(
        tenantId: UUID,
        updateTenantRequest: UpdateTenantRequest,
    ): ResponseEntity<TenantDto> {
        // TODO: Implement UpdateTenant command
        return ResponseEntity.notFound().build()
    }

    override fun deleteTenant(
        tenantId: UUID,
    ): ResponseEntity<Unit> {
        val deleted = mediator.send(DeleteTenant(id = TenantId.of(tenantId)))
        return if (deleted) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

    // ================== Environment operations ==================

    override fun listEnvironments(
        tenantId: UUID,
    ): ResponseEntity<EnvironmentListResponse> {
        val environments = mediator.query(ListEnvironments(tenantId = TenantId.of(tenantId)))
        return ResponseEntity.ok(EnvironmentListResponse(items = environments.map { it.toDto() }))
    }

    override fun createEnvironment(
        tenantId: UUID,
        createEnvironmentRequest: CreateEnvironmentRequest,
    ): ResponseEntity<EnvironmentDto> {
        val environment = mediator.send(
            CreateEnvironment(
                id = EnvironmentId.of(createEnvironmentRequest.id),
                tenantId = TenantId.of(tenantId),
                name = createEnvironmentRequest.name,
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(environment.toDto())
    }

    override fun getEnvironment(
        tenantId: UUID,
        environmentId: UUID,
    ): ResponseEntity<EnvironmentDto> {
        val environment = mediator.query(GetEnvironment(tenantId = TenantId.of(tenantId), id = EnvironmentId.of(environmentId)))
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(environment.toDto())
    }

    override fun updateEnvironment(
        tenantId: UUID,
        environmentId: UUID,
        updateEnvironmentRequest: UpdateEnvironmentRequest,
    ): ResponseEntity<EnvironmentDto> {
        val name = updateEnvironmentRequest.name
            ?: return ResponseEntity.badRequest().build()
        val environment = mediator.send(
            UpdateEnvironment(
                tenantId = TenantId.of(tenantId),
                id = EnvironmentId.of(environmentId),
                name = name,
            ),
        ) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(environment.toDto())
    }

    override fun deleteEnvironment(
        tenantId: UUID,
        environmentId: UUID,
    ): ResponseEntity<Unit> {
        val deleted = mediator.send(DeleteEnvironment(tenantId = TenantId.of(tenantId), id = EnvironmentId.of(environmentId)))
        return if (deleted) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }
}

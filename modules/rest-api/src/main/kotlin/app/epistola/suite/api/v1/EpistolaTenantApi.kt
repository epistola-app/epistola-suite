package app.epistola.suite.api.v1

import app.epistola.api.AttributesApi
import app.epistola.api.EnvironmentsApi
import app.epistola.api.TenantsApi
import app.epistola.api.model.AttributeDto
import app.epistola.api.model.AttributeListResponse
import app.epistola.api.model.CreateAttributeRequest
import app.epistola.api.model.CreateEnvironmentRequest
import app.epistola.api.model.CreateTenantRequest
import app.epistola.api.model.EnvironmentDto
import app.epistola.api.model.EnvironmentListResponse
import app.epistola.api.model.TenantDto
import app.epistola.api.model.TenantListResponse
import app.epistola.api.model.UpdateAttributeRequest
import app.epistola.api.model.UpdateEnvironmentRequest
import app.epistola.api.model.UpdateTenantRequest
import app.epistola.suite.api.v1.shared.toDto
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.commands.DeleteAttributeDefinition
import app.epistola.suite.attributes.commands.UpdateAttributeDefinition
import app.epistola.suite.attributes.queries.GetAttributeDefinition
import app.epistola.suite.attributes.queries.ListAttributeDefinitions
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantKey
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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class EpistolaTenantApi :
    TenantsApi,
    AttributesApi,
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
            id = TenantKey.of(createTenantRequest.id),
            name = createTenantRequest.name,
        ).execute()

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(tenant.toDto())
    }

    override fun getTenant(
        tenantId: String,
    ): ResponseEntity<TenantDto> {
        val tenant = GetTenant(id = TenantKey.of(tenantId)).query()
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
        val deleted = DeleteTenant(id = TenantKey.of(tenantId)).execute()
        return if (deleted) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

    // ================== Attribute operations ==================

    override fun listAttributes(
        tenantId: String,
    ): ResponseEntity<AttributeListResponse> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val attributes = ListAttributeDefinitions(tenantId = tenantIdComposite).query()
        return ResponseEntity.ok(AttributeListResponse(items = attributes.map { it.toDto() }))
    }

    override fun createAttribute(
        tenantId: String,
        createAttributeRequest: CreateAttributeRequest,
    ): ResponseEntity<AttributeDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val attributeIdComposite = AttributeId(AttributeKey.of(createAttributeRequest.key), tenantIdComposite)
        val attribute = CreateAttributeDefinition(
            id = attributeIdComposite,
            displayName = createAttributeRequest.description ?: createAttributeRequest.key,
        ).execute()
        return ResponseEntity.status(HttpStatus.CREATED).body(attribute.toDto())
    }

    override fun getAttribute(
        tenantId: String,
        attributeKey: String,
    ): ResponseEntity<AttributeDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val attributeIdComposite = AttributeId(AttributeKey.of(attributeKey), tenantIdComposite)
        val attribute = GetAttributeDefinition(id = attributeIdComposite).query()
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(attribute.toDto())
    }

    override fun updateAttribute(
        tenantId: String,
        attributeKey: String,
        updateAttributeRequest: UpdateAttributeRequest,
    ): ResponseEntity<AttributeDto> {
        val description = updateAttributeRequest.description
            ?: return ResponseEntity.badRequest().build()
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val attributeIdComposite = AttributeId(AttributeKey.of(attributeKey), tenantIdComposite)
        val attribute = UpdateAttributeDefinition(
            id = attributeIdComposite,
            displayName = description,
        ).execute() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(attribute.toDto())
    }

    override fun deleteAttribute(
        tenantId: String,
        attributeKey: String,
    ): ResponseEntity<Unit> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val attributeIdComposite = AttributeId(AttributeKey.of(attributeKey), tenantIdComposite)
        val deleted = DeleteAttributeDefinition(id = attributeIdComposite).execute()
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
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val environments = ListEnvironments(tenantId = tenantIdComposite).query()
        return ResponseEntity.ok(EnvironmentListResponse(items = environments.map { it.toDto() }))
    }

    override fun createEnvironment(
        tenantId: String,
        createEnvironmentRequest: CreateEnvironmentRequest,
    ): ResponseEntity<EnvironmentDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val environmentIdComposite = EnvironmentId(EnvironmentKey.of(createEnvironmentRequest.id), tenantIdComposite)
        val environment = CreateEnvironment(
            id = environmentIdComposite,
            name = createEnvironmentRequest.name,
        ).execute()
        return ResponseEntity.status(HttpStatus.CREATED).body(environment.toDto())
    }

    override fun getEnvironment(
        tenantId: String,
        environmentId: String,
    ): ResponseEntity<EnvironmentDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val environmentIdComposite = EnvironmentId(EnvironmentKey.of(environmentId), tenantIdComposite)
        val environment = GetEnvironment(id = environmentIdComposite).query()
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(environment.toDto())
    }

    override fun updateEnvironment(
        tenantId: String,
        environmentId: String,
        updateEnvironmentRequest: UpdateEnvironmentRequest,
    ): ResponseEntity<EnvironmentDto> {
        val name = updateEnvironmentRequest.name
            ?: return ResponseEntity.badRequest().build()
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val environmentIdComposite = EnvironmentId(EnvironmentKey.of(environmentId), tenantIdComposite)
        val environment = UpdateEnvironment(
            id = environmentIdComposite,
            name = name,
        ).execute() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(environment.toDto())
    }

    override fun deleteEnvironment(
        tenantId: String,
        environmentId: String,
    ): ResponseEntity<Unit> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val environmentIdComposite = EnvironmentId(EnvironmentKey.of(environmentId), tenantIdComposite)
        val deleted = DeleteEnvironment(id = environmentIdComposite).execute()
        return if (deleted) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }
}

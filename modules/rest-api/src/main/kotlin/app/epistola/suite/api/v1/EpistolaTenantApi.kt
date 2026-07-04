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
import app.epistola.suite.api.v1.shared.Pagination
import app.epistola.suite.api.v1.shared.toDto
import app.epistola.suite.attributes.AttributeNotFoundException
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.commands.DeleteAttributeDefinition
import app.epistola.suite.attributes.commands.UpdateAttributeDefinition
import app.epistola.suite.attributes.queries.GetAttributeDefinition
import app.epistola.suite.attributes.queries.ListAttributeDefinitions
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.EnvironmentNotFoundException
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeleteEnvironment
import app.epistola.suite.environments.commands.UpdateEnvironment
import app.epistola.suite.environments.queries.GetEnvironment
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.TenantNotFoundException
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.commands.DeleteTenant
import app.epistola.suite.tenants.commands.RenameTenant
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.tenants.queries.ListTenants
import app.epistola.suite.validation.ValidationException
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
        val slice = Pagination.paginate(tenants, page, size)
        return ResponseEntity.ok(
            TenantListResponse(
                items = slice.items.map { it.toDto() },
                page = slice.page,
            ),
        )
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
        val typedTenantId = TenantKey.of(tenantId)
        val tenant = GetTenant(id = typedTenantId).query()
            ?: throw TenantNotFoundException(typedTenantId)
        return ResponseEntity.ok(tenant.toDto())
    }

    override fun updateTenant(
        tenantId: String,
        updateTenantRequest: UpdateTenantRequest,
    ): ResponseEntity<TenantDto> {
        val typedTenantId = TenantKey.of(tenantId)
        // `name` is the only mutable field in the contract; a null name is a no-op
        // update that still returns the current tenant (or 404 if it doesn't exist).
        val name = updateTenantRequest.name
        val tenant = if (name != null) {
            RenameTenant(tenantId = typedTenantId, name = name).execute()
        } else {
            GetTenant(id = typedTenantId).query()
        } ?: throw TenantNotFoundException(typedTenantId)
        return ResponseEntity.ok(tenant.toDto())
    }

    override fun deleteTenant(
        tenantId: String,
    ): ResponseEntity<Unit> {
        val deleted = DeleteTenant(id = TenantKey.of(tenantId)).execute()
        return if (deleted) {
            ResponseEntity.noContent().build()
        } else {
            throw TenantNotFoundException(TenantKey.of(tenantId))
        }
    }

    // ================== Attribute operations ==================

    override fun listAttributes(
        tenantId: String,
        catalogId: String,
        page: Int,
        size: Int,
    ): ResponseEntity<AttributeListResponse> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val attributes = ListAttributeDefinitions(tenantId = tenantIdComposite, catalogKey = CatalogKey.of(catalogId)).query()
        val slice = Pagination.paginate(attributes, page, size)
        return ResponseEntity.ok(AttributeListResponse(items = slice.items.map { it.toDto() }, page = slice.page))
    }

    override fun createAttribute(
        tenantId: String,
        catalogId: String,
        createAttributeRequest: CreateAttributeRequest,
    ): ResponseEntity<AttributeDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val catalogIdComposite = CatalogId(CatalogKey.of(catalogId), tenantIdComposite)
        val attributeIdComposite = AttributeId(AttributeKey.of(createAttributeRequest.key), catalogIdComposite)
        // The `codeListBinding` ref always carries an explicit catalog —
        // it's the wire form, no defaulting. The constraint XOR check
        // (allowedValues vs codeListBinding) is enforced in
        // `CreateAttributeDefinition.init { … }` and surfaces as a 4xx.
        val codeListId = createAttributeRequest.codeListBinding?.let { binding ->
            CodeListId(
                key = CodeListKey.of(binding.slug),
                catalogId = CatalogId(CatalogKey.of(binding.catalog), tenantIdComposite),
            )
        }
        val attribute = CreateAttributeDefinition(
            id = attributeIdComposite,
            displayName = createAttributeRequest.displayName,
            allowedValues = createAttributeRequest.allowedValues ?: emptyList(),
            codeListId = codeListId,
        ).execute()
        return ResponseEntity.status(HttpStatus.CREATED).body(attribute.toDto())
    }

    override fun getAttribute(
        tenantId: String,
        catalogId: String,
        attributeKey: String,
    ): ResponseEntity<AttributeDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val attributeIdComposite = AttributeId(AttributeKey.of(attributeKey), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val attribute = GetAttributeDefinition(id = attributeIdComposite).query()
            ?: throw AttributeNotFoundException(attributeIdComposite.tenantKey, attributeIdComposite.catalogKey, attributeIdComposite.key)
        return ResponseEntity.ok(attribute.toDto())
    }

    override fun updateAttribute(
        tenantId: String,
        catalogId: String,
        attributeKey: String,
        updateAttributeRequest: UpdateAttributeRequest,
    ): ResponseEntity<AttributeDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val catalogIdComposite = CatalogId(CatalogKey.of(catalogId), tenantIdComposite)
        val attributeIdComposite = AttributeId(AttributeKey.of(attributeKey), catalogIdComposite)
        // PATCH semantics: pull the current definition so we can write a
        // full UpdateAttributeDefinition that only changes the fields the
        // client sent. UpdateAttributeDefinition isn't itself partial.
        val current = GetAttributeDefinition(id = attributeIdComposite).query()
            ?: throw AttributeNotFoundException(attributeIdComposite.tenantKey, attributeIdComposite.catalogKey, attributeIdComposite.key)
        val codeListId = if (updateAttributeRequest.codeListBinding != null) {
            CodeListId(
                key = CodeListKey.of(updateAttributeRequest.codeListBinding!!.slug),
                catalogId = CatalogId(CatalogKey.of(updateAttributeRequest.codeListBinding!!.catalog), tenantIdComposite),
            )
        } else {
            current.codeListId
        }
        val attribute = UpdateAttributeDefinition(
            id = attributeIdComposite,
            displayName = updateAttributeRequest.displayName ?: current.displayName,
            allowedValues = updateAttributeRequest.allowedValues ?: current.allowedValues,
            codeListId = codeListId,
        ).execute() ?: throw AttributeNotFoundException(attributeIdComposite.tenantKey, attributeIdComposite.catalogKey, attributeIdComposite.key)
        return ResponseEntity.ok(attribute.toDto())
    }

    override fun deleteAttribute(
        tenantId: String,
        catalogId: String,
        attributeKey: String,
    ): ResponseEntity<Unit> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val attributeIdComposite = AttributeId(AttributeKey.of(attributeKey), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val deleted = DeleteAttributeDefinition(id = attributeIdComposite).execute()
        return if (deleted) {
            ResponseEntity.noContent().build()
        } else {
            throw AttributeNotFoundException(attributeIdComposite.tenantKey, attributeIdComposite.catalogKey, attributeIdComposite.key)
        }
    }

    // ================== Environment operations ==================

    override fun listEnvironments(
        tenantId: String,
        page: Int,
        size: Int,
    ): ResponseEntity<EnvironmentListResponse> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val environments = ListEnvironments(tenantId = tenantIdComposite).query()
        val slice = Pagination.paginate(environments, page, size)
        return ResponseEntity.ok(EnvironmentListResponse(items = slice.items.map { it.toDto() }, page = slice.page))
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
            ?: throw EnvironmentNotFoundException(tenantIdComposite.key, environmentIdComposite.key)
        return ResponseEntity.ok(environment.toDto())
    }

    override fun updateEnvironment(
        tenantId: String,
        environmentId: String,
        updateEnvironmentRequest: UpdateEnvironmentRequest,
    ): ResponseEntity<EnvironmentDto> {
        val name = updateEnvironmentRequest.name
            ?: throw ValidationException(field = "name", message = "Environment name is required")
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val environmentIdComposite = EnvironmentId(EnvironmentKey.of(environmentId), tenantIdComposite)
        val environment = UpdateEnvironment(
            id = environmentIdComposite,
            name = name,
        ).execute() ?: throw EnvironmentNotFoundException(tenantIdComposite.key, environmentIdComposite.key)
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
            throw EnvironmentNotFoundException(tenantIdComposite.key, environmentIdComposite.key)
        }
    }
}

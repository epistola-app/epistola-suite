// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.AttributesApi
import app.epistola.api.EnvironmentsApi
import app.epistola.api.TenantsApi
import app.epistola.api.model.AttributeDto
import app.epistola.api.model.AttributeListResponse
import app.epistola.api.model.CreateAttributeRequest
import app.epistola.api.model.CreateEnvironmentRequest
import app.epistola.api.model.CreateTenantRequest
import app.epistola.api.model.DeploymentDto
import app.epistola.api.model.DeploymentEventDto
import app.epistola.api.model.DeploymentHistoryResponse
import app.epistola.api.model.DeploymentListResponse
import app.epistola.api.model.EnvironmentDto
import app.epistola.api.model.EnvironmentListResponse
import app.epistola.api.model.TenantDto
import app.epistola.api.model.TenantListResponse
import app.epistola.api.model.UpdateAttributeRequest
import app.epistola.api.model.UpdateEnvironmentRequest
import app.epistola.api.model.UpdateTenantRequest
import app.epistola.catalog.protocol.AttributeResource
import app.epistola.suite.api.v1.shared.ListSorting
import app.epistola.suite.api.v1.shared.Pagination
import app.epistola.suite.api.v1.shared.catalogAt
import app.epistola.suite.api.v1.shared.catalogId
import app.epistola.suite.api.v1.shared.catalogVersionAt
import app.epistola.suite.api.v1.shared.draftAt
import app.epistola.suite.api.v1.shared.ref
import app.epistola.suite.api.v1.shared.releasedResource
import app.epistola.suite.api.v1.shared.releasedResources
import app.epistola.suite.api.v1.shared.tenantKey
import app.epistola.suite.api.v1.shared.toDto
import app.epistola.suite.attributes.AttributeNotFoundException
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.commands.DeleteAttributeDefinition
import app.epistola.suite.attributes.commands.UpdateAttributeDefinition
import app.epistola.suite.attributes.model.VariantAttributeDefinition
import app.epistola.suite.attributes.queries.GetAttributeDefinition
import app.epistola.suite.attributes.queries.ListAttributeDefinitions
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogIdentityProperties
import app.epistola.suite.catalog.CatalogName
import app.epistola.suite.catalog.catalogName
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.versions.GetCatalogNames
import app.epistola.suite.catalog.versions.ResolvedCatalogVersion
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.EnvironmentNotFoundException
import app.epistola.suite.documents.NoReleaseDeployedException
import app.epistola.suite.environments.CatalogDeployment
import app.epistola.suite.environments.DeploymentAction
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeleteEnvironment
import app.epistola.suite.environments.commands.DeployRelease
import app.epistola.suite.environments.commands.UndeployRelease
import app.epistola.suite.environments.commands.UpdateEnvironment
import app.epistola.suite.environments.queries.CountDeploymentHistory
import app.epistola.suite.environments.queries.GetEnvironment
import app.epistola.suite.environments.queries.ListDeploymentHistory
import app.epistola.suite.environments.queries.ListDeployments
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
class EpistolaTenantApi(
    private val identity: CatalogIdentityProperties,
) : TenantsApi,
    AttributesApi,
    EnvironmentsApi {

    // ================== Tenant operations ==================

    override fun listTenants(
        q: String?,
        page: Int,
        size: Int,
        sort: String?,
        direction: String,
    ): ResponseEntity<TenantListResponse> {
        // This endpoint has no sortable columns; reject a caller-supplied sort rather than ignore it.
        ListSorting.rejectUnsupportedSort(sort, direction)
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
            id = TenantKey.of(createTenantRequest.key),
            name = createTenantRequest.name,
        ).execute()

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(tenant.toDto())
    }

    override fun getTenant(
        tenantKey: String,
    ): ResponseEntity<TenantDto> {
        val typedTenantId = TenantKey.of(tenantKey)
        val tenant = GetTenant(id = typedTenantId).query()
            ?: throw TenantNotFoundException(typedTenantId)
        return ResponseEntity.ok(tenant.toDto())
    }

    override fun updateTenant(
        tenantKey: String,
        updateTenantRequest: UpdateTenantRequest,
    ): ResponseEntity<TenantDto> {
        val typedTenantId = TenantKey.of(tenantKey)
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
        tenantKey: String,
    ): ResponseEntity<Unit> {
        val deleted = DeleteTenant(id = TenantKey.of(tenantKey)).execute()
        return if (deleted) {
            ResponseEntity.noContent().build()
        } else {
            throw TenantNotFoundException(TenantKey.of(tenantKey))
        }
    }

    // ================== Attribute operations ==================

    override fun listAttributes(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        page: Int,
        size: Int,
        sort: String?,
        direction: String,
    ): ResponseEntity<AttributeListResponse> {
        // This endpoint has no sortable columns; reject a caller-supplied sort rather than ignore it.
        ListSorting.rejectUnsupportedSort(sort, direction)
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val items = if (at.isDraft) {
            val bindingCatalogs = bindingCatalogsOf(at)
            ListAttributeDefinitions(tenantId = TenantId(at.catalog.tenantKey), catalogKey = at.catalogKey, catalogVersion = at.ref).query()
                .map { it.toDto(at.catalog, it.codeListCatalogKey?.let(bindingCatalogs::get)) }
        } else {
            // Released content is paged in memory: it is assembled whole, and bounded by the catalog.
            at.releasedResources<AttributeResource>(ATTRIBUTE).map { it.toDto(at) }
        }
        val slice = Pagination.paginate(items, page, size)
        return ResponseEntity.ok(AttributeListResponse(items = slice.items, page = slice.page))
    }

    override fun createAttribute(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        createAttributeRequest: CreateAttributeRequest,
    ): ResponseEntity<AttributeDto> {
        // An attribute keeps no description; refuse it rather than drop it.
        if (createAttributeRequest.description != null) throw ApiOperationNotImplementedException("createAttribute.description")
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        // The constraint XOR check (allowedValues vs codeListBinding) is enforced in
        // `CreateAttributeDefinition.init { … }` and surfaces as a 4xx.
        val codeListId = createAttributeRequest.codeListBinding?.let { binding ->
            draft.codeListAt(binding.registry, binding.namespace, binding.catalogKey, binding.key)
        }
        val attribute = CreateAttributeDefinition(
            id = draft.attributeId(createAttributeRequest.key),
            displayName = createAttributeRequest.displayName,
            allowedValues = createAttributeRequest.allowedValues ?: emptyList(),
            codeListId = codeListId,
            catalogVersion = draft.ref,
        ).execute()
        return ResponseEntity.status(HttpStatus.CREATED).body(attribute.toDto(draft))
    }

    override fun getAttribute(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        attributeKey: String,
    ): ResponseEntity<AttributeDto> {
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val attribute = if (at.isDraft) {
            GetAttributeDefinition(id = at.attributeId(attributeKey), catalogVersion = at.ref).query()?.toDto(at)
        } else {
            at.releasedResource<AttributeResource>(ATTRIBUTE, attributeKey)?.toDto(at)
        } ?: throw at.attributeNotFound(attributeKey)
        return ResponseEntity.ok(attribute)
    }

    override fun updateAttribute(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        attributeKey: String,
        updateAttributeRequest: UpdateAttributeRequest,
    ): ResponseEntity<AttributeDto> {
        // An attribute keeps no description; refuse it rather than drop it.
        if (updateAttributeRequest.description != null) throw ApiOperationNotImplementedException("updateAttribute.description")
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val id = draft.attributeId(attributeKey)
        // PATCH semantics: pull the current definition so we can write a
        // full UpdateAttributeDefinition that only changes the fields the
        // client sent. UpdateAttributeDefinition isn't itself partial.
        val current = GetAttributeDefinition(id = id, catalogVersion = draft.ref).query() ?: throw draft.attributeNotFound(attributeKey)
        val codeListId = updateAttributeRequest.codeListBinding?.let { binding ->
            draft.codeListAt(binding.registry, binding.namespace, binding.catalogKey, binding.key)
        } ?: current.codeListId
        val attribute = UpdateAttributeDefinition(
            id = id,
            displayName = updateAttributeRequest.displayName ?: current.displayName,
            allowedValues = updateAttributeRequest.allowedValues ?: current.allowedValues,
            codeListId = codeListId,
            catalogVersion = draft.ref,
        ).execute() ?: throw draft.attributeNotFound(attributeKey)
        return ResponseEntity.ok(attribute.toDto(draft))
    }

    override fun deleteAttribute(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        attributeKey: String,
    ): ResponseEntity<Unit> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        if (!DeleteAttributeDefinition(id = draft.attributeId(attributeKey), catalogVersion = draft.ref).execute()) throw draft.attributeNotFound(attributeKey)
        return ResponseEntity.noContent().build()
    }

    private fun ResolvedCatalogVersion.attributeId(key: String) = AttributeId(AttributeKey.of(key), catalogId)

    private fun ResolvedCatalogVersion.attributeNotFound(key: String) = AttributeNotFoundException(catalog.tenantKey, catalogKey, AttributeKey.of(key))

    /**
     * The code list a binding names. A binding's registry and namespace left out are the attribute's
     * own catalog's; the catalog must exist by that name.
     */
    private fun ResolvedCatalogVersion.codeListAt(registry: String?, namespace: String?, catalogKey: String, key: String): CodeListId {
        val catalog = catalogAt(
            tenantKey.value,
            registry ?: this.catalog.registry,
            namespace ?: this.catalog.namespace,
            catalogKey,
        )
        return CodeListId(key = CodeListKey.of(key), catalogId = catalog.catalogId)
    }

    /** The catalogs the draft's attributes bind code lists from, by key. */
    private fun bindingCatalogsOf(at: ResolvedCatalogVersion): Map<CatalogKey, Catalog> = ListCatalogs(at.catalog.tenantKey).query().associateBy { it.id }

    private fun VariantAttributeDefinition.toDto(at: ResolvedCatalogVersion) = toDto(
        at.catalog,
        codeListCatalogKey?.let { key -> if (key == at.catalogKey) at.catalog else GetCatalog(at.catalog.tenantKey, key).query() },
    )

    // ================== Environment operations ==================

    override fun listEnvironments(
        tenantKey: String,
        page: Int,
        size: Int,
        sort: String?,
        direction: String,
    ): ResponseEntity<EnvironmentListResponse> {
        // This endpoint has no sortable columns; reject a caller-supplied sort rather than ignore it.
        ListSorting.rejectUnsupportedSort(sort, direction)
        val environments = ListEnvironments(tenantId = TenantId(TenantKey.of(tenantKey))).query()
        val slice = Pagination.paginate(environments, page, size)
        return ResponseEntity.ok(EnvironmentListResponse(items = slice.items.map { it.toDto() }, page = slice.page))
    }

    override fun createEnvironment(
        tenantKey: String,
        createEnvironmentRequest: CreateEnvironmentRequest,
    ): ResponseEntity<EnvironmentDto> {
        val environment = CreateEnvironment(
            id = EnvironmentId(EnvironmentKey.of(createEnvironmentRequest.key), TenantId(TenantKey.of(tenantKey))),
            name = createEnvironmentRequest.name,
        ).execute()
        return ResponseEntity.status(HttpStatus.CREATED).body(environment.toDto())
    }

    override fun getEnvironment(
        tenantKey: String,
        environmentKey: String,
    ): ResponseEntity<EnvironmentDto> {
        val id = environmentId(tenantKey, environmentKey)
        val environment = GetEnvironment(id = id).query()
            ?: throw EnvironmentNotFoundException(id.tenantKey, id.key)
        return ResponseEntity.ok(environment.toDto())
    }

    override fun updateEnvironment(
        tenantKey: String,
        environmentKey: String,
        updateEnvironmentRequest: UpdateEnvironmentRequest,
    ): ResponseEntity<EnvironmentDto> {
        val name = updateEnvironmentRequest.name
            ?: throw ValidationException(field = "name", message = "Environment name is required")
        val id = environmentId(tenantKey, environmentKey)
        val environment = UpdateEnvironment(id = id, name = name).execute()
            ?: throw EnvironmentNotFoundException(id.tenantKey, id.key)
        return ResponseEntity.ok(environment.toDto())
    }

    override fun deleteEnvironment(
        tenantKey: String,
        environmentKey: String,
    ): ResponseEntity<Unit> {
        val id = environmentId(tenantKey, environmentKey)
        if (!DeleteEnvironment(id = id).execute()) throw EnvironmentNotFoundException(id.tenantKey, id.key)
        return ResponseEntity.noContent().build()
    }

    // ================== Deployments ==================
    //
    // An environment serves any number of releases of each catalog; generation naming it renders
    // the highest, or the highest satisfying the request's version selector. Deploying checks the
    // releases of other catalogs the release renders with, and deploys them along on request. A
    // dependency release the tenant does not have is not fetched from its registry: it is reported
    // UNAVAILABLE.

    override fun listEnvironmentDeployments(
        tenantKey: String,
        environmentKey: String,
    ): ResponseEntity<DeploymentListResponse> {
        val environment = existingEnvironment(tenantKey, environmentKey)
        val deployments = ListDeployments(environment.tenantKey, environmentKey = environment.key).query()
        val catalogs = GetCatalogNames(environment.tenantKey, deployments.map { it.catalogKey }).query()
        return ResponseEntity.ok(
            DeploymentListResponse(items = deployments.mapNotNull { deployment -> catalogs[deployment.catalogKey]?.let { deployment.toDto(it) } }),
        )
    }

    override fun deployRelease(
        tenantKey: String,
        environmentKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        releaseVersion: String,
        includeDependencies: Boolean,
    ): ResponseEntity<DeploymentDto> {
        val catalog = catalogAt(tenantKey, registry, namespace, catalogKey)
        val deployment = DeployRelease(environmentId(tenantKey, environmentKey), catalog.id, releaseVersion, includeDependencies).execute()
        return ResponseEntity.ok(deployment.toDto(catalog.catalogName))
    }

    /** Stops serving the release; 404 when the environment does not serve that release of the catalog. */
    override fun undeployRelease(
        tenantKey: String,
        environmentKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        releaseVersion: String,
    ): ResponseEntity<Unit> {
        val environment = existingEnvironment(tenantKey, environmentKey)
        val catalog = catalogAt(tenantKey, registry, namespace, catalogKey)
        if (!UndeployRelease(environment, catalog.id, releaseVersion).execute()) {
            throw NoReleaseDeployedException(environment.tenantKey, environment.key, catalog.id, releaseVersion)
        }
        return ResponseEntity.noContent().build()
    }

    override fun listDeploymentHistory(
        tenantKey: String,
        environmentKey: String,
        page: Int,
        size: Int,
    ): ResponseEntity<DeploymentHistoryResponse> {
        val environment = existingEnvironment(tenantKey, environmentKey)
        val history = ListDeploymentHistory(environment, limit = Pagination.limitOf(size), offset = Pagination.offsetOf(page, size)).query()
        val catalogs by lazy { GetCatalogNames(environment.tenantKey, history.map { it.catalogKey }).query() }
        return ResponseEntity.ok(
            DeploymentHistoryResponse(
                // BRIDGE: override entries reach the API with the contract's OVERRIDE_* actions.
                items = history.filter { it.version != null && it.action in setOf(DeploymentAction.DEPLOYED, DeploymentAction.UNDEPLOYED) }.map { entry ->
                    // An entry recorded the catalog's name; one from before it did reads the catalog's current name.
                    val catalog = if (entry.registry == null || entry.namespace == null) catalogs[entry.catalogKey] else null
                    DeploymentEventDto(
                        registry = entry.registry ?: catalog?.registry ?: identity.defaultRegistry,
                        namespace = entry.namespace ?: catalog?.namespace ?: CatalogIdentityProperties.defaultNamespace(environment.tenantKey),
                        catalogKey = entry.catalogKey.value,
                        action = if (entry.action == DeploymentAction.DEPLOYED) DeploymentEventDto.Action.DEPLOYED else DeploymentEventDto.Action.UNDEPLOYED,
                        releaseVersion = entry.version!!,
                        changedAt = entry.changedAt,
                        changedBy = entry.changedByName,
                    )
                },
                page = Pagination.pageMeta(page, size, CountDeploymentHistory(environment).query()),
            ),
        )
    }

    private fun environmentId(tenantKey: String, environmentKey: String) = EnvironmentId(EnvironmentKey.of(environmentKey), TenantId(TenantKey.of(tenantKey)))

    private fun existingEnvironment(tenantKey: String, environmentKey: String): EnvironmentId {
        val id = environmentId(tenantKey, environmentKey)
        GetEnvironment(id = id).query() ?: throw EnvironmentNotFoundException(id.tenantKey, id.key)
        return id
    }

    private fun CatalogDeployment.toDto(catalog: CatalogName) = DeploymentDto(
        registry = catalog.registry,
        namespace = catalog.namespace,
        catalogKey = catalogKey.value,
        releaseVersion = version,
        deployedAt = deployedAt,
    )

    private companion object {
        const val ATTRIBUTE = "attribute"
    }
}

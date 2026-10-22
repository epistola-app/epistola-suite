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
import app.epistola.suite.api.v1.shared.ETags
import app.epistola.suite.api.v1.shared.ListSorting
import app.epistola.suite.api.v1.shared.Pagination
import app.epistola.suite.api.v1.shared.catalogAt
import app.epistola.suite.api.v1.shared.catalogId
import app.epistola.suite.api.v1.shared.catalogVersionAt
import app.epistola.suite.api.v1.shared.created
import app.epistola.suite.api.v1.shared.draftAt
import app.epistola.suite.api.v1.shared.okWithETag
import app.epistola.suite.api.v1.shared.readOnlyHere
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
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.queries.ListReleaseDependencies
import app.epistola.suite.catalog.releases.CatalogReleaseNotFoundException
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.releases.GetCatalogNames
import app.epistola.suite.catalog.releases.ListCatalogReleases
import app.epistola.suite.catalog.releases.ResolvedCatalogRelease
import app.epistola.suite.common.Revisions
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
import app.epistola.suite.environments.commands.DeployCatalogRelease
import app.epistola.suite.environments.commands.UndeployCatalogRelease
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
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import app.epistola.suite.validation.validate
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import app.epistola.api.model.CatalogReleaseStatus as ApiCatalogReleaseStatus

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

        return tenant.toDto().let { created(it.key, it) }
    }

    override fun getTenant(
        tenantKey: String,
    ): ResponseEntity<TenantDto> {
        val typedTenantId = TenantKey.of(tenantKey)
        val tenant = GetTenant(id = typedTenantId).query()
            ?: throw TenantNotFoundException(typedTenantId)
        return okWithETag(tenant.revision, tenant.toDto())
    }

    override fun updateTenant(
        tenantKey: String,
        updateTenantRequest: UpdateTenantRequest,
        ifMatch: String?,
    ): ResponseEntity<TenantDto> {
        val typedTenantId = TenantKey.of(tenantKey)
        val expected = ETags.expected(ifMatch)
        // `name` is the only mutable field in the contract; a null name is a no-op
        // update that still returns the current tenant (or 404 if it doesn't exist).
        val name = updateTenantRequest.name
        val tenant = if (name != null) {
            RenameTenant(tenantId = typedTenantId, name = name, expectedRevision = expected).execute()
        } else {
            GetTenant(id = typedTenantId).query()?.also { Revisions.refuse("Tenant '$tenantKey'", expected, it.revision) }
        } ?: throw TenantNotFoundException(typedTenantId)
        return okWithETag(tenant.revision, tenant.toDto())
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
        release: String,
        page: Int,
        size: Int,
        sort: String?,
        direction: String,
    ): ResponseEntity<AttributeListResponse> {
        // This endpoint has no sortable columns; reject a caller-supplied sort rather than ignore it.
        ListSorting.rejectUnsupportedSort(sort, direction)
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, release)
        val items = if (at.isDraft) {
            val readOnly = at.catalog.readOnlyHere()
            ListAttributeDefinitions(tenantId = TenantId(at.catalog.tenantKey), catalogKey = at.catalogKey, catalogVersion = at.ref).query()
                .map { it.toDto(at.catalog, readOnly) }
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
        release: String,
        createAttributeRequest: CreateAttributeRequest,
    ): ResponseEntity<AttributeDto> {
        // An attribute keeps no description; refuse it rather than drop it.
        if (createAttributeRequest.description != null) throw ApiOperationNotImplementedException("createAttribute.description")
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        // The constraint XOR check (allowedValues vs codeListBinding) is enforced in
        // `CreateAttributeDefinition.init { … }` and surfaces as a 4xx.
        val codeListId = createAttributeRequest.codeListBinding?.let { binding ->
            draft.codeListAt(binding.catalogKey, binding.key)
        }
        val attribute = CreateAttributeDefinition(
            id = draft.attributeId(createAttributeRequest.key),
            displayName = createAttributeRequest.displayName,
            allowedValues = createAttributeRequest.allowedValues ?: emptyList(),
            codeListId = codeListId,
            catalogVersion = draft.ref,
        ).execute()
        return attribute.toDto(draft).let { created(it.key, it) }
    }

    override fun getAttribute(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        attributeKey: String,
    ): ResponseEntity<AttributeDto> {
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, release)
        if (!at.isDraft) {
            return ResponseEntity.ok(at.releasedResource<AttributeResource>(ATTRIBUTE, attributeKey)?.toDto(at) ?: throw at.attributeNotFound(attributeKey))
        }
        val attribute = GetAttributeDefinition(id = at.attributeId(attributeKey), catalogVersion = at.ref).query() ?: throw at.attributeNotFound(attributeKey)
        return okWithETag(attribute.revision, attribute.toDto(at))
    }

    override fun updateAttribute(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        attributeKey: String,
        updateAttributeRequest: UpdateAttributeRequest,
        ifMatch: String?,
    ): ResponseEntity<AttributeDto> {
        // An attribute keeps no description; refuse it rather than drop it.
        if (updateAttributeRequest.description != null) throw ApiOperationNotImplementedException("updateAttribute.description")
        val clearsBinding = updateAttributeRequest.clearCodeListBinding == true
        validate("clearCodeListBinding", !(clearsBinding && updateAttributeRequest.codeListBinding != null)) { "Send a codeListBinding or clearCodeListBinding, not both" }
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        val id = draft.attributeId(attributeKey)
        // PATCH semantics: pull the current definition so we can write a
        // full UpdateAttributeDefinition that only changes the fields the
        // client sent. UpdateAttributeDefinition isn't itself partial.
        val current = GetAttributeDefinition(id = id, catalogVersion = draft.ref).query() ?: throw draft.attributeNotFound(attributeKey)
        val codeListId = updateAttributeRequest.codeListBinding?.let { binding ->
            draft.codeListAt(binding.catalogKey, binding.key)
        } ?: current.codeListId.takeUnless { clearsBinding }
        val attribute = UpdateAttributeDefinition(
            id = id,
            displayName = updateAttributeRequest.displayName ?: current.displayName,
            allowedValues = updateAttributeRequest.allowedValues ?: current.allowedValues,
            codeListId = codeListId,
            catalogVersion = draft.ref,
            // The merge above read the attribute at `current`; a write in between must not be lost.
            expectedRevision = ETags.expected(ifMatch) ?: current.revision,
        ).execute() ?: throw draft.attributeNotFound(attributeKey)
        return okWithETag(attribute.revision, attribute.toDto(draft))
    }

    override fun deleteAttribute(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        attributeKey: String,
    ): ResponseEntity<Unit> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        if (!DeleteAttributeDefinition(id = draft.attributeId(attributeKey), catalogVersion = draft.ref).execute()) throw draft.attributeNotFound(attributeKey)
        return ResponseEntity.noContent().build()
    }

    private fun ResolvedCatalogRelease.attributeId(key: String) = AttributeId(AttributeKey.of(key), catalogId)

    private fun ResolvedCatalogRelease.attributeNotFound(key: String) = AttributeNotFoundException(catalog.tenantKey, catalogKey, AttributeKey.of(key))

    /**
     * The code list a binding names: in the attribute's own catalog when it names no alias or the
     * catalog's own key, else in the catalog the draft declares under that alias (contract ADR 0003,
     * ADR 0034). An alias the draft does not declare is refused, as the contract says.
     */
    private fun ResolvedCatalogRelease.codeListAt(alias: String?, key: String): CodeListId {
        if (alias == null || alias == catalogKey.value) return CodeListId(key = CodeListKey.of(key), catalogId = catalogId)
        val declared = ListCatalogDependencies(tenantKey, catalogKey, catalogVersion = ref).query().firstOrNull { it.alias == alias }
            ?: throw ValidationException(
                "codeListBinding.catalogKey",
                "'$alias' is not an alias this draft declares a dependency under: declare the catalog the code list is in first (updateCatalogDependencies)",
                ValidationCode.CATALOG_DEPENDENCY_UNRESOLVED,
            )
        return CodeListId(key = CodeListKey.of(key), catalogId = app.epistola.suite.common.ids.CatalogId(CatalogKey.of(declared.catalogKey), TenantId(tenantKey)))
    }

    private fun VariantAttributeDefinition.toDto(at: ResolvedCatalogRelease) = toDto(at.catalog)

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
            production = createEnvironmentRequest.production ?: false,
        ).execute()
        return environment.toDto().let { created(it.key, it) }
    }

    override fun getEnvironment(
        tenantKey: String,
        environmentKey: String,
    ): ResponseEntity<EnvironmentDto> {
        val id = environmentId(tenantKey, environmentKey)
        val environment = GetEnvironment(id = id).query()
            ?: throw EnvironmentNotFoundException(id.tenantKey, id.key)
        return okWithETag(environment.revision, environment.toDto())
    }

    override fun updateEnvironment(
        tenantKey: String,
        environmentKey: String,
        updateEnvironmentRequest: UpdateEnvironmentRequest,
        ifMatch: String?,
    ): ResponseEntity<EnvironmentDto> {
        val id = environmentId(tenantKey, environmentKey)
        // A partial update: what is left out stays as it is.
        val current = GetEnvironment(id = id).query() ?: throw EnvironmentNotFoundException(id.tenantKey, id.key)
        val environment = UpdateEnvironment(
            id = id,
            name = updateEnvironmentRequest.name ?: current.name,
            production = updateEnvironmentRequest.production,
            expectedRevision = ETags.expected(ifMatch) ?: current.revision,
        ).execute()
            ?: throw EnvironmentNotFoundException(id.tenantKey, id.key)
        return okWithETag(environment.revision, environment.toDto())
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
    // An environment serves catalog versions (contract ADR 0003): any number of releases of each
    // catalog, and at most one draft. Generation naming it renders the draft for a request naming
    // no version, else the highest release, or the highest satisfying the request's version range.
    // Deploying a release checks the releases of other catalogs it renders with, and deploys them
    // along on request. A dependency release the tenant does not have is not fetched from its
    // registry: it is reported UNAVAILABLE.

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

    override fun deployCatalogRelease(
        tenantKey: String,
        environmentKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        includeDependencies: Boolean,
        replace: Boolean,
    ): ResponseEntity<DeploymentDto> {
        val catalog = catalogAt(tenantKey, registry, namespace, catalogKey)
        val deployment = DeployCatalogRelease(environmentId(tenantKey, environmentKey), catalog.id, release, includeDependencies, replace = replace).execute()
        return ResponseEntity.ok(deployment.toDto(catalog.catalogName))
    }

    /** Stops serving the release; 404 when the environment does not deploy that release of the catalog. */
    override fun undeployCatalogRelease(
        tenantKey: String,
        environmentKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
    ): ResponseEntity<Unit> {
        val environment = existingEnvironment(tenantKey, environmentKey)
        val catalog = catalogAt(tenantKey, registry, namespace, catalogKey)
        if (!UndeployCatalogRelease(environment, catalog.id, release).execute()) {
            throw NoReleaseDeployedException(environment.tenantKey, environment.key, catalog.id, release)
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
                items = history.map { entry ->
                    // An entry recorded the catalog's name; one from before it did reads the catalog's current name.
                    val catalog = if (entry.registry == null || entry.namespace == null) catalogs[entry.catalogKey] else null
                    DeploymentEventDto(
                        registry = entry.registry ?: catalog?.registry ?: identity.defaultRegistry,
                        namespace = entry.namespace ?: catalog?.namespace ?: CatalogIdentityProperties.defaultNamespace(environment.tenantKey),
                        catalogKey = entry.catalogKey.value,
                        action = when (entry.action) {
                            DeploymentAction.DEPLOYED -> DeploymentEventDto.Action.DEPLOYED
                            DeploymentAction.UNDEPLOYED -> DeploymentEventDto.Action.UNDEPLOYED
                            DeploymentAction.RELEASED -> DeploymentEventDto.Action.RELEASED
                        },
                        releaseId = entry.versionKey.orEmpty(),
                        status = if (entry.version == null) ApiCatalogReleaseStatus.DRAFT else ApiCatalogReleaseStatus.RELEASED,
                        version = entry.version,
                        releaseName = if (entry.version == null || entry.action == DeploymentAction.RELEASED) entry.draftLabel else null,
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
        releaseId = versionId.orEmpty(),
        status = if (isDraft) ApiCatalogReleaseStatus.DRAFT else ApiCatalogReleaseStatus.RELEASED,
        version = version,
        releaseName = draftName,
        deployedAt = deployedAt,
        lastUsedAt = lastUsedAt,
        documentsRequested = documentsRequested,
    )

    private companion object {
        const val ATTRIBUTE = "attribute"
    }
}

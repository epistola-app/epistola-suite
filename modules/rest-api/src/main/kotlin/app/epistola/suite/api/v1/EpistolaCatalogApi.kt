// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.CatalogsApi
import app.epistola.api.model.BehindReleaseDto
import app.epistola.api.model.CatalogChangesDto
import app.epistola.api.model.CatalogDependencyDto
import app.epistola.api.model.CatalogDependencyListResponse
import app.epistola.api.model.CatalogDto
import app.epistola.api.model.CatalogInstallResultDto
import app.epistola.api.model.CatalogListResponse
import app.epistola.api.model.CatalogUpgradeDiff
import app.epistola.api.model.CatalogVersionDto
import app.epistola.api.model.CatalogVersionListResponse
import app.epistola.api.model.CatalogVersionRenumberPreview
import app.epistola.api.model.CreateCatalogVersionRequest
import app.epistola.api.model.ImportCatalogResponse
import app.epistola.api.model.ReleaseCatalogVersionRequest
import app.epistola.api.model.ReleaseCatalogVersionResponse
import app.epistola.api.model.ReleaseDependencyDto
import app.epistola.api.model.ReleasedChangeDto
import app.epistola.api.model.RemovedCatalogResourceDto
import app.epistola.api.model.RenumberCatalogVersionRequest
import app.epistola.api.model.RenumberWarningDto
import app.epistola.api.model.ResourceChangeDto
import app.epistola.api.model.UpdateCatalogDependenciesRequest
import app.epistola.api.model.UpgradeCatalogResponse
import app.epistola.suite.api.v1.shared.ListSorting
import app.epistola.suite.api.v1.shared.Pagination
import app.epistola.suite.api.v1.shared.catalogAt
import app.epistola.suite.api.v1.shared.catalogVersionAt
import app.epistola.suite.api.v1.shared.draftAt
import app.epistola.suite.api.v1.shared.ref
import app.epistola.suite.api.v1.shared.tenantKey
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogIdentityProperties
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.commands.AuthoredImportMode
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.commands.UpgradeCatalog
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.queries.CatalogResourceChanges
import app.epistola.suite.catalog.queries.CatalogResourceState
import app.epistola.suite.catalog.queries.GetCatalogResourceChanges
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.queries.ListReleaseDependencies
import app.epistola.suite.catalog.queries.PreviewCatalogUpgrade
import app.epistola.suite.catalog.queries.UpgradeResourceChange
import app.epistola.suite.catalog.versions.CatalogVersion
import app.epistola.suite.catalog.versions.CatalogVersionNotFoundException
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionStatus
import app.epistola.suite.catalog.versions.CreateCatalogVersion
import app.epistola.suite.catalog.versions.DeleteCatalogVersion
import app.epistola.suite.catalog.versions.GetCatalogNames
import app.epistola.suite.catalog.versions.GetCatalogVersion
import app.epistola.suite.catalog.versions.ListCatalogVersions
import app.epistola.suite.catalog.versions.PreviewCatalogVersionRenumber
import app.epistola.suite.catalog.versions.RenumberCatalogVersion
import app.epistola.suite.catalog.versions.ResolvedCatalogVersion
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import app.epistola.api.model.CatalogVersionStatus as CatalogVersionStatusDto

/**
 * Catalogs and their versions (contract ADR 0002/0003). An authored catalog has any number of open
 * drafts, each its own copy, and releases; a subscribed catalog has the releases it installed. A
 * draft's resource paths (`…/versions/{catalogVersion}/…`) read and write that draft.
 */
@RestController
@RequestMapping("/api")
class EpistolaCatalogApi(
    private val identity: CatalogIdentityProperties,
) : CatalogsApi {

    override fun listCatalogs(
        tenantKey: String,
        page: Int,
        size: Int,
        sort: String?,
        direction: String,
    ): ResponseEntity<CatalogListResponse> {
        // This endpoint has no sortable columns; reject a caller-supplied sort rather than ignore it.
        ListSorting.rejectUnsupportedSort(sort, direction)
        val tenant = TenantKey.of(tenantKey)
        val catalogs = ListCatalogs(tenant).query()
        val slice = Pagination.paginate(catalogs, page, size)
        return ResponseEntity.ok(CatalogListResponse(page = slice.page, items = slice.items.map { it.toDto() }))
    }

    override fun previewCatalogUpgrade(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
    ): ResponseEntity<CatalogUpgradeDiff> {
        val catalog = catalogAt(tenantKey, registry, namespace, catalogKey)
        val diff = PreviewCatalogUpgrade(tenantKey = catalog.tenantKey, catalogKey = catalog.id).query()

        fun keys(changes: List<UpgradeResourceChange>) = changes.map { "${it.type}/${it.slug}" }

        return ResponseEntity.ok(
            CatalogUpgradeDiff(
                registry = catalog.registry,
                namespace = catalog.namespace,
                catalogKey = catalog.id.value,
                newVersion = diff.newVersion,
                upgradeAvailable = diff.hasChanges,
                added = keys(diff.added),
                removed = keys(diff.removed),
                changed = keys(diff.changed),
                unchanged = keys(diff.unchanged),
                conflicts = diff.conflicts,
                blockedByConflicts = diff.hasConflicts,
                previousVersion = diff.previousVersion,
            ),
        )
    }

    /** An upgrade reconciles the whole manifest (issue #850): there is nothing to choose. */
    override fun upgradeCatalog(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
    ): ResponseEntity<UpgradeCatalogResponse> {
        val catalog = catalogAt(tenantKey, registry, namespace, catalogKey)
        val result = UpgradeCatalog(tenantKey = catalog.tenantKey, catalogKey = catalog.id).execute()

        return ResponseEntity.ok(
            UpgradeCatalogResponse(
                newVersion = result.newVersion,
                installResults = result.installResults.map {
                    CatalogInstallResultDto(
                        type = it.type,
                        key = it.slug,
                        status = CatalogInstallResultDto.Status.valueOf(it.status.name),
                        errorMessage = it.errorMessage,
                    )
                },
                removedResources = result.removedResources.map {
                    RemovedCatalogResourceDto(type = it.type, key = it.slug)
                },
                aborted = result.aborted,
                previousVersion = result.previousVersion,
            ),
        )
    }

    /**
     * Imports a catalog archive under the name the archive gives it, into the catalog's current draft
     * (its highest). Naming another registry, namespace or draft is not supported yet: the archive
     * names the catalog, so a draft number cannot be resolved before the archive is read.
     */
    override fun importCatalog(
        tenantKey: String,
        file: MultipartFile,
        registry: String?,
        namespace: String?,
        catalogVersion: String?,
        catalogType: String,
        authoredMode: String,
    ): ResponseEntity<ImportCatalogResponse> {
        // The import names the catalog as the archive does, and fills its current draft; it cannot
        // take another name or a draft number from the caller yet.
        if (registry != null) throw ApiOperationNotImplementedException("importCatalog.registry")
        if (namespace != null) throw ApiOperationNotImplementedException("importCatalog.namespace")
        if (catalogVersion != null) throw ApiOperationNotImplementedException("importCatalog.catalogVersion")
        val tenant = TenantKey.of(tenantKey)
        val type = CatalogType.valueOf(catalogType.ifBlank { "AUTHORED" })
        val mode = AuthoredImportMode.valueOf(authoredMode.ifBlank { "MERGE" })

        val result = ImportCatalogZip(
            tenantKey = tenant,
            zipBytes = file.bytes,
            catalogType = type,
            authoredMode = mode,
            // REST is non-interactive: an AUTHORED migratable-old import migrates
            // without a confirmation round-trip (the UI prompts; REST does not).
            confirmMigration = true,
        ).execute()

        // Named for any importer: viewing catalogs is not what an import asks for. For the same reason
        // the response leaves out the version imported into, which takes viewing the catalog's versions.
        val name = GetCatalogNames(tenant, listOf(result.catalogKey)).query()[result.catalogKey]
        return ResponseEntity.ok(
            ImportCatalogResponse(
                registry = name?.registry ?: identity.defaultRegistry,
                namespace = name?.namespace ?: CatalogIdentityProperties.defaultNamespace(tenant),
                catalogKey = result.catalogKey.value,
                catalogName = result.catalogName,
                installed = result.results.count { it.status == InstallStatus.INSTALLED },
                updated = result.results.count { it.status == InstallStatus.UPDATED },
                failed = result.results.count { it.status == InstallStatus.FAILED },
                total = result.results.size,
                aborted = result.aborted,
                catalogVersion = null,
            ),
        )
    }

    // ================== Versions ==================

    override fun listCatalogVersions(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        status: CatalogVersionStatusDto?,
        page: Int,
        size: Int,
    ): ResponseEntity<CatalogVersionListResponse> {
        val catalog = catalogAt(tenantKey, registry, namespace, catalogKey)
        // The versions come in SemVer order, which SQL does not sort by; a catalog's version count
        // is bounded by its release history, so the page is cut here.
        val versions = ListCatalogVersions(catalog.tenantKey, catalog.id, status?.toDomain()).query()
        val slice = Pagination.paginate(versions, page, size)
        return ResponseEntity.ok(CatalogVersionListResponse(items = slice.items.map { it.toDto(catalog) }, page = slice.page))
    }

    override fun getCatalogVersion(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
    ): ResponseEntity<CatalogVersionDto> {
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        return ResponseEntity.ok(at.version.toDto(at.catalog))
    }

    /** Opens a draft, as a copy of its base or of the version `from` names. */
    override fun createCatalogVersion(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        createCatalogVersionRequest: CreateCatalogVersionRequest,
    ): ResponseEntity<CatalogVersionDto> {
        val catalog = catalogAt(tenantKey, registry, namespace, catalogKey)
        val version = CreateCatalogVersion(catalog.tenantKey, catalog.id, createCatalogVersionRequest.version, createCatalogVersionRequest.from).execute()
        return ResponseEntity.status(HttpStatus.CREATED)
            .location(versionLocation(version.version))
            .body(version.toDto(catalog))
    }

    override fun deleteCatalogVersion(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
    ): ResponseEntity<Unit> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        DeleteCatalogVersion(draft.tenantKey, draft.catalogKey, draft.version.version).execute()
        return ResponseEntity.noContent().build()
    }

    override fun renumberCatalogVersion(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        renumberCatalogVersionRequest: RenumberCatalogVersionRequest,
    ): ResponseEntity<CatalogVersionDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val renumbered = RenumberCatalogVersion(
            tenantKey = draft.tenantKey,
            catalogKey = draft.catalogKey,
            version = draft.version.version,
            to = renumberCatalogVersionRequest.version,
            acknowledgeWarnings = renumberCatalogVersionRequest.acknowledgeWarnings ?: false,
        ).execute()
        return ResponseEntity.ok()
            .location(versionLocation(renumbered.version, replacing = draft.version.version))
            .body(renumbered.toDto(draft.catalog))
    }

    override fun previewCatalogVersionRenumber(
        to: String,
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
    ): ResponseEntity<CatalogVersionRenumberPreview> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val warnings = PreviewCatalogVersionRenumber(draft.tenantKey, draft.catalogKey, draft.version.version, to).query()
        return ResponseEntity.ok(
            CatalogVersionRenumberPreview(
                from = draft.version.version,
                to = to,
                warnings = warnings.map { RenumberWarningDto(kind = it.kind, message = it.text, versions = it.versions) },
            ),
        )
    }

    /**
     * Releases the draft under its own number. A draft behind releases needs `acknowledgedBehind` to
     * name each one (409 otherwise). With no draft open above it, the draft carries on as the next
     * patch (`openedDraft`); otherwise it is discarded.
     */
    override fun releaseCatalogVersion(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        releaseCatalogVersionRequest: ReleaseCatalogVersionRequest,
    ): ResponseEntity<ReleaseCatalogVersionResponse> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val result = ReleaseCatalogVersion(
            tenantKey = draft.tenantKey,
            catalogKey = draft.catalogKey,
            version = draft.version.version,
            notes = releaseCatalogVersionRequest.notes,
            acknowledgedBehind = releaseCatalogVersionRequest.acknowledgedBehind.orEmpty().toSet(),
        ).execute()
        val released = GetCatalogVersion(draft.tenantKey, draft.catalogKey, result.version).query()
            ?: throw CatalogVersionNotFoundException(draft.catalogKey, result.version)
        return ResponseEntity.ok(
            ReleaseCatalogVersionResponse(
                catalogVersion = released.toDto(draft.catalog),
                unchangedContent = result.unchangedContent,
                draftsBehind = result.draftsBehind,
                openedDraft = result.openedDraft,
            ),
        )
    }

    // ================== Changes and readiness ==================

    override fun getCatalogChanges(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
    ): ResponseEntity<CatalogChangesDto> {
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        if (!at.isDraft) {
            // A released version has no changes, and nothing to release.
            return ResponseEntity.ok(CatalogChangesDto(releasable = false, dependenciesChanged = false, changes = emptyList(), base = baseOf(at)))
        }
        return ResponseEntity.ok(GetCatalogResourceChanges(at.tenantKey, at.catalogKey, catalogVersion = at.ref).query().toDto(at))
    }

    override fun markCatalogReady(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
    ): ResponseEntity<CatalogChangesDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        return ResponseEntity.ok(MarkCatalogReady(draft.tenantKey, draft.catalogKey, catalogVersion = draft.ref).execute().toDto(draft))
    }

    // ================== Dependencies ==================

    override fun listCatalogDependencies(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
    ): ResponseEntity<CatalogDependencyListResponse> {
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        return ResponseEntity.ok(CatalogDependencyListResponse(items = dependenciesOf(at).map { it.toDependencyDto() }))
    }

    /** A dependency without a registry is on the default registry, as the contract says. */
    override fun updateCatalogDependencies(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        updateCatalogDependenciesRequest: UpdateCatalogDependenciesRequest,
    ): ResponseEntity<CatalogDependencyListResponse> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val declared = UpdateCatalogDependencies(
            tenantKey = draft.tenantKey,
            catalogKey = draft.catalogKey,
            dependencies = updateCatalogDependenciesRequest.dependencies.map {
                DeclaredCatalogDependency(
                    registry = it.registry ?: identity.defaultRegistry,
                    namespace = it.namespace,
                    catalogKey = it.catalogKey,
                    version = it.version,
                )
            },
            catalogVersion = draft.ref,
        ).execute()
        return ResponseEntity.ok(CatalogDependencyListResponse(items = declared.map { it.toDependencyDto() }))
    }

    // ================== Helpers ==================

    private fun Catalog.toDto(): CatalogDto {
        val authored = type == CatalogType.AUTHORED
        return CatalogDto(
            registry = registry,
            namespace = namespace,
            catalogKey = id.value,
            name = name,
            description = description,
            type = CatalogDto.Type.valueOf(type.name),
            draftVersions = if (authored) {
                ListCatalogVersions(tenantKey, id, CatalogVersionStatus.DRAFT).query().map { it.version }.sortedWith(LOWEST_FIRST)
            } else {
                emptyList()
            },
            releasedVersion = if (authored) releasedVersion else installedReleaseVersion,
            fingerprint = if (authored) releasedFingerprint else installedFingerprint,
        )
    }

    /**
     * A version as the contract describes it. A draft lists the releases its declared dependencies
     * are locked to now; a release, the ones it was cut with. Which dependencies a release reaches
     * only through another is not recorded, so every one listed counts as direct. A draft lists the
     * releases it is behind, each with what it changed against the release below it.
     */
    private fun CatalogVersion.toDto(catalog: Catalog): CatalogVersionDto {
        val draft = status == CatalogVersionStatus.DRAFT
        val dependencies = if (draft) {
            ListCatalogDependencies(catalog.tenantKey, catalog.id, catalogVersion = CatalogVersionRef(catalog.tenantKey, catalog.id, version)).query()
        } else {
            ListReleaseDependencies(catalog.tenantKey, catalog.id, version).query()
        }
        return CatalogVersionDto(
            version = version,
            status = if (draft) CatalogVersionStatusDto.DRAFT else CatalogVersionStatusDto.RELEASED,
            behind = behind.map { release ->
                BehindReleaseDto(
                    version = release.version,
                    changes = release.changes.map { ReleasedChangeDto(type = it.type, key = it.key, change = ReleasedChangeDto.Change.forValue(it.change)) },
                )
            },
            dependencies = dependencies.mapNotNull { dependency ->
                dependency.releaseVersion?.let {
                    ReleaseDependencyDto(
                        registry = dependency.registry,
                        namespace = dependency.namespace,
                        catalogKey = dependency.catalogKey,
                        releaseVersion = it,
                        direct = true,
                    )
                }
            },
            deployedTo = deployedTo,
            base = base,
            createdFrom = createdFrom,
            createdAt = createdAt,
            releasedAt = releasedAt,
            notes = notes,
            fingerprint = fingerprint,
            contentRetained = contentRetained,
        )
    }

    private fun CatalogDependencyDeclaration.toDependencyDto() = CatalogDependencyDto(
        registry = registry,
        namespace = namespace,
        catalogKey = catalogKey,
        version = version,
        resolvedVersion = releaseVersion,
    )

    /** For a draft, the dependencies declared now, with their locks; for a release, its pins. */
    private fun dependenciesOf(at: ResolvedCatalogVersion): List<CatalogDependencyDeclaration> = if (at.isDraft) {
        ListCatalogDependencies(at.tenantKey, at.catalogKey, catalogVersion = at.ref).query()
    } else {
        ListReleaseDependencies(at.tenantKey, at.catalogKey, at.version.version).query()
    }

    /**
     * A draft's changes against its base. A resource changed and marked ready is `ready`; one the last
     * release has unchanged is not listed. Releasing waits on every changed resource not marked ready.
     */
    private fun CatalogResourceChanges.toDto(draft: ResolvedCatalogVersion): CatalogChangesDto {
        val changes = resources.mapNotNull { resource ->
            val change = when (resource.state) {
                CatalogResourceState.RELEASED -> null

                CatalogResourceState.REMOVED -> ResourceChangeDto.Change.REMOVED

                CatalogResourceState.NEW, CatalogResourceState.MODIFIED, CatalogResourceState.UNKNOWN -> when {
                    resource.ready -> ResourceChangeDto.Change.READY
                    resource.state == CatalogResourceState.NEW -> ResourceChangeDto.Change.NEW
                    else -> ResourceChangeDto.Change.MODIFIED
                }
            }
            change?.let { ResourceChangeDto(type = resource.type, key = resource.slug, change = it) }
        }
        val base = draft.version.base
        val declared = ListCatalogDependencies(draft.tenantKey, draft.catalogKey, catalogVersion = draft.ref).query().map { it.requirement }.toSet()
        val released = base?.let { ListReleaseDependencies(draft.tenantKey, draft.catalogKey, it).query() }.orEmpty().map { it.requirement }.toSet()
        return CatalogChangesDto(
            releasable = notReady.isEmpty(),
            dependenciesChanged = declared != released,
            changes = changes,
            base = base,
        )
    }

    private val CatalogDependencyDeclaration.requirement get() = listOf(registry, namespace, catalogKey, version)

    /**
     * A released version's base: the one it recorded, else (a release cut before releases recorded
     * it) the highest released version below it.
     */
    private fun baseOf(at: ResolvedCatalogVersion): String? {
        at.version.base?.let { return it }
        val own = SemVer.parseOrNull(at.version.version) ?: return null
        return ListCatalogVersions(at.tenantKey, at.catalogKey, CatalogVersionStatus.RELEASED).query()
            .mapNotNull { version -> SemVer.parseOrNull(version.version)?.let { it to version.version } }
            .filter { (semVer, _) -> semVer < own }
            .maxByOrNull { (semVer, _) -> semVer }
            ?.second
    }

    /** `…/versions/{version}`, from the request's own address. */
    private fun versionLocation(version: String, replacing: String? = null) = ServletUriComponentsBuilder.fromCurrentRequestUri().build().toUri().let { current ->
        val path = current.path.removeSuffix("/")
        val base = if (replacing != null) path.removeSuffix("/$replacing") else path
        current.resolve("$base/$version")
    }

    private fun CatalogVersionStatusDto.toDomain() = when (this) {
        CatalogVersionStatusDto.DRAFT -> CatalogVersionStatus.DRAFT
        CatalogVersionStatusDto.RELEASED -> CatalogVersionStatus.RELEASED
    }

    private companion object {
        val LOWEST_FIRST: Comparator<String> = compareBy<String, SemVer?>(nullsLast()) { SemVer.parseOrNull(it) }.thenBy { it }
    }
}

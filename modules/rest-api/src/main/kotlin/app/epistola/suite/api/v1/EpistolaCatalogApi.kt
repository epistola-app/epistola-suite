// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.CatalogsApi
import app.epistola.api.model.CatalogChangesDto
import app.epistola.api.model.CatalogDto
import app.epistola.api.model.CatalogInstallResultDto
import app.epistola.api.model.CatalogListResponse
import app.epistola.api.model.CatalogReleaseDto
import app.epistola.api.model.CatalogReleaseListResponse
import app.epistola.api.model.CatalogUpgradeDiff
import app.epistola.api.model.ImportCatalogResponse
import app.epistola.api.model.ReleaseCatalogRequest
import app.epistola.api.model.ReleaseCatalogResponse
import app.epistola.api.model.ReleaseDependencyDto
import app.epistola.api.model.RemovedCatalogResourceDto
import app.epistola.api.model.ResourceChangeDto
import app.epistola.api.model.UpgradeCatalogResponse
import app.epistola.suite.api.v1.shared.ListSorting
import app.epistola.suite.api.v1.shared.Pagination
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.commands.AuthoredImportMode
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.UpgradeCatalog
import app.epistola.suite.catalog.queries.CatalogReleaseSummary
import app.epistola.suite.catalog.queries.CatalogResourceState
import app.epistola.suite.catalog.queries.CountCatalogReleases
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.GetCatalogRelease
import app.epistola.suite.catalog.queries.GetCatalogResourceChanges
import app.epistola.suite.catalog.queries.ListCatalogDrafts
import app.epistola.suite.catalog.queries.ListCatalogReleases
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.queries.ListReleaseDependencies
import app.epistola.suite.catalog.queries.PreviewCatalogUpgrade
import app.epistola.suite.catalog.queries.UpgradeResourceChange
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.documents.ReleaseNotFoundException
import app.epistola.suite.environments.queries.ListDeployments
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.stencils.model.StencilVersionStatus
import app.epistola.suite.stencils.queries.ListStencilVersions
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.contracts.ContractPublishConflictException
import app.epistola.suite.templates.contracts.commands.PublishContractVersion
import app.epistola.suite.templates.queries.versions.GetDraft
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api")
class EpistolaCatalogApi : CatalogsApi {

    override fun listCatalogs(
        tenantId: String,
        page: Int,
        size: Int,
        sort: String?,
        direction: String,
    ): ResponseEntity<CatalogListResponse> {
        // This endpoint has no sortable columns; reject a caller-supplied sort rather than ignore it.
        ListSorting.rejectUnsupportedSort(sort, direction)
        val tenantKey = TenantKey.of(tenantId)
        val catalogs = ListCatalogs(tenantKey).query()
        val slice = Pagination.paginate(catalogs, page, size)
        return ResponseEntity.ok(
            CatalogListResponse(
                page = slice.page,
                items = slice.items.map { catalog ->
                    val authored = catalog.type == CatalogType.AUTHORED
                    CatalogDto(
                        slug = catalog.id.value,
                        name = catalog.name,
                        description = catalog.description,
                        type = CatalogDto.Type.valueOf(catalog.type.name),
                        releasedVersion = if (authored) catalog.releasedVersion else catalog.installedReleaseVersion,
                        fingerprint = if (authored) catalog.releasedFingerprint else catalog.installedFingerprint,
                    )
                },
            ),
        )
    }

    override fun previewCatalogUpgrade(
        tenantId: String,
        catalogId: String,
    ): ResponseEntity<CatalogUpgradeDiff> {
        val diff = PreviewCatalogUpgrade(
            tenantKey = TenantKey.of(tenantId),
            catalogKey = CatalogKey.of(catalogId),
        ).query()

        fun keys(changes: List<UpgradeResourceChange>) = changes.map { "${it.type}/${it.slug}" }

        return ResponseEntity.ok(
            CatalogUpgradeDiff(
                catalogId = diff.catalogKey.value,
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

    override fun listCatalogReleases(
        tenantId: String,
        catalogId: String,
        page: Int,
        size: Int,
    ): ResponseEntity<CatalogReleaseListResponse> {
        val tenant = TenantKey.of(tenantId)
        val catalog = CatalogKey.of(catalogId)
        GetCatalog(tenant, catalog).query() ?: throw CatalogNotFoundException(catalog)
        val releases = ListCatalogReleases(tenant, catalog, offset = Pagination.offsetOf(page, size), limit = Pagination.limitOf(size)).query()
        val deployments = ListDeployments(tenant, catalog).query().groupBy({ it.version }, { it.environmentKey.value })
        return ResponseEntity.ok(
            CatalogReleaseListResponse(
                items = releases.map { it.toDto(tenant, catalog, deployments[it.version].orEmpty()) },
                page = Pagination.pageMeta(page, size, CountCatalogReleases(tenant, catalog).query()),
            ),
        )
    }

    override fun getCatalogRelease(
        tenantId: String,
        catalogId: String,
        releaseVersion: String,
    ): ResponseEntity<CatalogReleaseDto> {
        val tenant = TenantKey.of(tenantId)
        val catalog = CatalogKey.of(catalogId)
        val release = GetCatalogRelease(tenant, catalog, releaseVersion).query()
            ?: throw ReleaseNotFoundException(tenant, catalog, releaseVersion)
        val deployedTo = ListDeployments(tenant, catalog).query().filter { it.version == releaseVersion }.map { it.environmentKey.value }
        return ResponseEntity.ok(
            CatalogReleaseDto(
                releaseVersion = release.version,
                fingerprint = release.fingerprint,
                releasedAt = release.releasedAt,
                notes = release.notes,
                contentRetained = release.retained,
                dependencies = dependenciesOf(tenant, catalog, release.version),
                deployedTo = deployedTo,
            ),
        )
    }

    /**
     * Interim, until the working copy has a ready mark (WP3): a template or stencil with a draft is
     * `modified`, and every other change is already published, so the next release takes it: `ready`.
     * The release command does not refuse drafts yet, so `releasable` reports what it would do.
     */
    override fun getCatalogChanges(
        tenantId: String,
        catalogId: String,
    ): ResponseEntity<CatalogChangesDto> = ResponseEntity.ok(changesOf(TenantKey.of(tenantId), CatalogKey.of(catalogId)))

    /** Publishes every draft in the catalog: what marking ready means while a release reads published versions (WP3). */
    override fun markCatalogReady(
        tenantId: String,
        catalogId: String,
    ): ResponseEntity<CatalogChangesDto> {
        val tenant = TenantKey.of(tenantId)
        val catalog = CatalogKey.of(catalogId)
        val catalogRef = CatalogId(catalog, TenantId(tenant))
        val drafts = ListCatalogDrafts(tenant, catalog).query()
        for ((template, variant) in drafts.variants) {
            val variantId = VariantId(variant, TemplateId(template, catalogRef))
            GetDraft(variantId).query()?.let { PublishVersion(VersionId(it.id, variantId)).execute() }
        }
        for (template in drafts.contracts) {
            val published = PublishContractVersion(TemplateId(template, catalogRef)).execute()
            if (published != null && !published.published) {
                throw ContractPublishConflictException(published.breakingChanges.map { it.description })
            }
        }
        for (stencil in drafts.stencils) {
            val stencilId = StencilId(stencil, catalogRef)
            ListStencilVersions(stencilId = stencilId, status = StencilVersionStatus.DRAFT).query().firstOrNull()?.let {
                PublishStencilVersion(StencilVersionId(it.id, stencilId)).execute()
            }
        }
        return ResponseEntity.ok(changesOf(tenant, catalog))
    }

    private fun changesOf(tenant: TenantKey, catalog: CatalogKey): CatalogChangesDto {
        val changes = GetCatalogResourceChanges(tenant, catalog).query()
        val drafts = ListCatalogDrafts(tenant, catalog).query()
        val draftTemplates = drafts.templates
        val draftStencils = drafts.stencils.map { it.value }.toSet()
        fun hasDraft(type: String, slug: String) = (type == "template" && slug in draftTemplates) || (type == "stencil" && slug in draftStencils)
        val items = changes.resources.mapNotNull { resource ->
            val change = when {
                resource.state == CatalogResourceState.REMOVED -> ResourceChangeDto.Change.REMOVED

                hasDraft(resource.type, resource.slug) ->
                    if (resource.state == CatalogResourceState.NEW) ResourceChangeDto.Change.NEW else ResourceChangeDto.Change.MODIFIED

                resource.state == CatalogResourceState.RELEASED -> null

                else -> ResourceChangeDto.Change.READY
            }
            change?.let { ResourceChangeDto(type = resource.type, slug = resource.slug, change = it) }
        }
        return CatalogChangesDto(
            latestRelease = changes.latestVersion,
            releasable = changes.hasUnreleasedChanges,
            changes = items,
        )
    }

    private fun dependenciesOf(tenant: TenantKey, catalog: CatalogKey, version: String) = ListReleaseDependencies(tenant, catalog, version).query().map {
        ReleaseDependencyDto(catalogId = it.catalogKey.value, releaseVersion = it.version, direct = it.direct)
    }

    private fun CatalogReleaseSummary.toDto(tenant: TenantKey, catalog: CatalogKey, deployedTo: List<String>) = CatalogReleaseDto(
        releaseVersion = version,
        fingerprint = fingerprint,
        releasedAt = releasedAt,
        notes = notes,
        contentRetained = retained,
        dependencies = dependenciesOf(tenant, catalog, version),
        deployedTo = deployedTo,
    )

    override fun releaseCatalog(
        tenantId: String,
        catalogId: String,
        releaseCatalogRequest: ReleaseCatalogRequest,
    ): ResponseEntity<ReleaseCatalogResponse> {
        val result = ReleaseCatalogVersion(
            tenantKey = TenantKey.of(tenantId),
            catalogKey = CatalogKey.of(catalogId),
            version = releaseCatalogRequest.releaseVersion,
            notes = releaseCatalogRequest.notes,
        ).execute()

        return ResponseEntity.ok(
            ReleaseCatalogResponse(
                version = result.version,
                fingerprint = result.fingerprint,
                releasedAt = result.releasedAt,
                unchangedContent = result.unchangedContent,
                previousVersion = result.previousVersion,
            ),
        )
    }

    /** An upgrade reconciles the whole manifest (issue #850): there is nothing to choose. */
    override fun upgradeCatalog(
        tenantId: String,
        catalogId: String,
    ): ResponseEntity<UpgradeCatalogResponse> {
        val result = UpgradeCatalog(
            tenantKey = TenantKey.of(tenantId),
            catalogKey = CatalogKey.of(catalogId),
        ).execute()

        return ResponseEntity.ok(
            UpgradeCatalogResponse(
                newVersion = result.newVersion,
                installResults = result.installResults.map {
                    CatalogInstallResultDto(
                        type = it.type,
                        slug = it.slug,
                        status = CatalogInstallResultDto.Status.valueOf(it.status.name),
                        errorMessage = it.errorMessage,
                    )
                },
                removedResources = result.removedResources.map {
                    RemovedCatalogResourceDto(type = it.type, slug = it.slug)
                },
                aborted = result.aborted,
                previousVersion = result.previousVersion,
            ),
        )
    }

    override fun importCatalog(
        tenantId: String,
        file: MultipartFile,
        catalogType: String,
        authoredMode: String,
    ): ResponseEntity<ImportCatalogResponse> {
        val tenantKey = TenantKey.of(tenantId)
        val type = CatalogType.valueOf(catalogType.ifBlank { "AUTHORED" })
        val mode = AuthoredImportMode.valueOf(authoredMode.ifBlank { "MERGE" })

        val result = ImportCatalogZip(
            tenantKey = tenantKey,
            zipBytes = file.bytes,
            catalogType = type,
            authoredMode = mode,
            // REST is non-interactive: an AUTHORED migratable-old import migrates
            // without a confirmation round-trip (the UI prompts; REST does not).
            confirmMigration = true,
        ).execute()

        val installed = result.results.count { it.status == InstallStatus.INSTALLED }
        val updated = result.results.count { it.status == InstallStatus.UPDATED }
        val failed = result.results.count { it.status == InstallStatus.FAILED }

        return ResponseEntity.ok(
            ImportCatalogResponse(
                catalogKey = result.catalogKey.value,
                catalogName = result.catalogName,
                installed = installed,
                updated = updated,
                failed = failed,
                total = result.results.size,
                aborted = result.aborted,
            ),
        )
    }
}

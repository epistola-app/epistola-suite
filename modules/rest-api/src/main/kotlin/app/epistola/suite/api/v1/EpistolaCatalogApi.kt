// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.CatalogsApi
import app.epistola.api.model.AvailableReleaseDto
import app.epistola.api.model.BehindReleaseDto
import app.epistola.api.model.CatalogChangesDto
import app.epistola.api.model.CatalogDependencyDto
import app.epistola.api.model.CatalogDependencyListResponse
import app.epistola.api.model.CatalogDraftReleasePreview
import app.epistola.api.model.CatalogDraftSummary
import app.epistola.api.model.CatalogDto
import app.epistola.api.model.CatalogListResponse
import app.epistola.api.model.CatalogUpgradePreview
import app.epistola.api.model.CatalogVersionDto
import app.epistola.api.model.CatalogVersionListResponse
import app.epistola.api.model.CreateCatalogDraftRequest
import app.epistola.api.model.ImportCatalogResponse
import app.epistola.api.model.ReleaseCatalogDraftRequest
import app.epistola.api.model.ReleaseCatalogDraftResponse
import app.epistola.api.model.ReleaseDependencyDto
import app.epistola.api.model.ReleasedChangeDto
import app.epistola.api.model.ReleasedDependencyDto
import app.epistola.api.model.RenameCatalogDraftRequest
import app.epistola.api.model.ResourceChangeDto
import app.epistola.api.model.UpdateCatalogDependenciesRequest
import app.epistola.api.model.UpgradeCatalogResponse
import app.epistola.suite.api.v1.shared.ListSorting
import app.epistola.suite.api.v1.shared.Pagination
import app.epistola.suite.api.v1.shared.catalogAt
import app.epistola.suite.api.v1.shared.catalogVersionAt
import app.epistola.suite.api.v1.shared.draftAt
import app.epistola.suite.api.v1.shared.draftRef
import app.epistola.suite.api.v1.shared.ref
import app.epistola.suite.api.v1.shared.tenantKey
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogIdentityProperties
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.ImportCatalogZip
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
import app.epistola.suite.catalog.versions.AssessDraftNumber
import app.epistola.suite.catalog.versions.CatalogVersion
import app.epistola.suite.catalog.versions.CatalogVersionNotFoundException
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionStatus
import app.epistola.suite.catalog.versions.CreateCatalogDraft
import app.epistola.suite.catalog.versions.DeleteCatalogDraft
import app.epistola.suite.catalog.versions.GetCatalogDraft
import app.epistola.suite.catalog.versions.GetCatalogNames
import app.epistola.suite.catalog.versions.GetCatalogVersion
import app.epistola.suite.catalog.versions.ListCatalogVersions
import app.epistola.suite.catalog.versions.RenameCatalogDraft
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

    /**
     * What the catalog's source offers above the highest installed release. The source offers its
     * latest release only, so that is the one listed when it is higher; its changes are against the
     * installed release. Which releases it pins is not read from the source yet: none are listed.
     */
    override fun previewCatalogUpgrade(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
    ): ResponseEntity<CatalogUpgradePreview> {
        val catalog = catalogAt(tenantKey, registry, namespace, catalogKey)
        val diff = PreviewCatalogUpgrade(tenantKey = catalog.tenantKey, catalogKey = catalog.id).query()
        val installed = catalog.installedReleaseVersion ?: diff.previousVersion.orEmpty()
        val higher = SemVer.parseOrNull(diff.newVersion)?.let { offered -> SemVer.parseOrNull(installed)?.let { offered > it } ?: true } == true
        fun changes(kind: ReleasedChangeDto.Change, of: List<app.epistola.suite.catalog.queries.UpgradeResourceChange>) = of.map { ReleasedChangeDto(type = it.type, key = it.slug, change = kind) }
        val available = if (higher) {
            listOf(
                AvailableReleaseDto(
                    version = diff.newVersion,
                    changes = changes(ReleasedChangeDto.Change.ADDED, diff.added) + changes(ReleasedChangeDto.Change.MODIFIED, diff.changed) + changes(ReleasedChangeDto.Change.REMOVED, diff.removed),
                    dependencies = emptyList(),
                ),
            )
        } else {
            emptyList()
        }
        return ResponseEntity.ok(
            CatalogUpgradePreview(
                registry = catalog.registry,
                namespace = catalog.namespace,
                catalogKey = catalog.id.value,
                installedVersion = installed,
                available = available,
            ),
        )
    }

    /**
     * Installs a newer release of a subscribed catalog from its source, which offers its latest
     * release only: a `releaseVersion` other than that one is not offered (404).
     */
    override fun upgradeCatalog(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        releaseVersion: String?,
    ): ResponseEntity<UpgradeCatalogResponse> {
        val catalog = catalogAt(tenantKey, registry, namespace, catalogKey)
        if (releaseVersion != null) {
            val offered = PreviewCatalogUpgrade(tenantKey = catalog.tenantKey, catalogKey = catalog.id).query().newVersion
            if (offered != releaseVersion) throw CatalogVersionNotFoundException(catalog.id, releaseVersion)
        }
        val result = UpgradeCatalog(tenantKey = catalog.tenantKey, catalogKey = catalog.id).execute()
        if (result.aborted) throw IllegalStateException("Upgrading ${catalog.id.value} to ${result.newVersion} failed; nothing changed")
        return ResponseEntity.ok(
            UpgradeCatalogResponse(
                registry = catalog.registry,
                namespace = catalog.namespace,
                catalogKey = catalog.id.value,
                releaseVersion = result.newVersion,
                newlyInstalled = result.previousVersion != result.newVersion,
                total = result.installResults.size,
                installedDependencies = emptyList(),
                presentDependencies = emptyList(),
            ),
        )
    }

    /**
     * Installs the release the archive carries, in the catalog it names; naming another registry or
     * namespace is not supported yet.
     */
    override fun importCatalog(
        tenantKey: String,
        file: MultipartFile,
        registry: String?,
        namespace: String?,
        catalogType: String?,
    ): ResponseEntity<ImportCatalogResponse> {
        if (registry != null) throw ApiOperationNotImplementedException("importCatalog.registry")
        if (namespace != null) throw ApiOperationNotImplementedException("importCatalog.namespace")
        val tenant = TenantKey.of(tenantKey)
        val result = ImportCatalogZip(
            tenantKey = tenant,
            zipBytes = file.bytes,
            catalogType = catalogType?.takeIf { it.isNotBlank() }?.let(CatalogType::valueOf),
            // REST is non-interactive: an archive of an older wire version migrates without a prompt.
            confirmMigration = true,
        ).execute()

        val name = GetCatalogNames(tenant, listOf(result.catalogKey)).query()[result.catalogKey]
        return ResponseEntity.ok(
            ImportCatalogResponse(
                registry = name?.registry ?: identity.defaultRegistry,
                namespace = name?.namespace ?: CatalogIdentityProperties.defaultNamespace(tenant),
                catalogKey = result.catalogKey.value,
                catalogName = result.catalogName,
                releaseVersion = requireNotNull(result.releaseVersion),
                newlyInstalled = result.newlyInstalled,
                total = result.results.size,
                installedDependencies = result.installedDependencies,
                presentDependencies = result.presentDependencies,
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

    /** Starts a draft, as a copy of the version `from` names, or of the highest release. */
    override fun createCatalogDraft(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        createCatalogDraftRequest: CreateCatalogDraftRequest,
    ): ResponseEntity<CatalogVersionDto> {
        val catalog = catalogAt(tenantKey, registry, namespace, catalogKey)
        val draft = CreateCatalogDraft(catalog.tenantKey, catalog.id, createCatalogDraftRequest.name, createCatalogDraftRequest.from).execute()
        return ResponseEntity.status(HttpStatus.CREATED)
            .location(versionLocation(draft.versionId))
            .body(draft.toDto(catalog))
    }

    /** Discards a draft. The next version, a convention of this suite, starts again on the latest release. */
    override fun deleteCatalogDraft(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
    ): ResponseEntity<Unit> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        DeleteCatalogDraft(draft.tenantKey, draft.catalogKey, draft.draftRef).execute()
        return ResponseEntity.noContent().build()
    }

    override fun renameCatalogDraft(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        renameCatalogDraftRequest: RenameCatalogDraftRequest,
    ): ResponseEntity<CatalogVersionDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val renamed = RenameCatalogDraft(draft.tenantKey, draft.catalogKey, draft.draftRef, renameCatalogDraftRequest.name).execute()
        return ResponseEntity.ok(renamed.toDto(draft.catalog))
    }

    /** The number releasing the draft would give it, from the kind of change against its base. */
    override fun previewCatalogDraftRelease(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
    ): ResponseEntity<CatalogDraftReleasePreview> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val assessment = AssessDraftNumber(draft.tenantKey, draft.catalogKey, draft.draftRef).query()
            ?: throw CatalogVersionNotFoundException(draft.catalogKey, catalogVersion)
        return ResponseEntity.ok(
            CatalogDraftReleasePreview(
                base = assessment.base,
                changeKind = assessment.needs?.let { CatalogDraftReleasePreview.ChangeKind.forValue(it.name.lowercase()) },
                reasons = assessment.reasons,
                needed = assessment.needed,
                suggested = assessment.suggested,
            ),
        )
    }

    /**
     * Releases a draft, numbered as asked or as its changes need against its base. A draft behind
     * releases needs `acknowledgedBehind` to name each one (409 otherwise); one whose declarations
     * name drafts of other catalogs needs `releaseDependencies` (409 `release-dependencies-unreleased`).
     */
    override fun releaseCatalogDraft(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        releaseCatalogDraftRequest: ReleaseCatalogDraftRequest,
    ): ResponseEntity<ReleaseCatalogDraftResponse> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val result = ReleaseCatalogVersion(
            tenantKey = draft.tenantKey,
            catalogKey = draft.catalogKey,
            version = releaseCatalogDraftRequest.version,
            notes = releaseCatalogDraftRequest.notes,
            acknowledgedBehind = releaseCatalogDraftRequest.acknowledgedBehind.orEmpty().toSet(),
            catalogVersion = draft.ref,
            releaseDependencies = releaseCatalogDraftRequest.releaseDependencies == true,
        ).execute()
        val released = GetCatalogVersion(draft.tenantKey, draft.catalogKey, result.version).query()
            ?: throw CatalogVersionNotFoundException(draft.catalogKey, result.version)
        return ResponseEntity.ok(
            ReleaseCatalogDraftResponse(
                catalogVersion = released.toDto(draft.catalog),
                unchangedContent = result.unchangedContent,
                draftsBehind = result.draftsBehind,
                releasedDependencies = result.releasedDependencies.map {
                    ReleasedDependencyDto(registry = it.registry, namespace = it.namespace, catalogKey = it.catalogKey, versionId = it.versionId, releaseVersion = it.releaseVersion)
                },
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
                    releaseVersion = it.releaseVersion,
                    versionId = it.versionId,
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
            drafts = if (authored) {
                ListCatalogVersions(tenantKey, id, CatalogVersionStatus.DRAFT).query()
                    .map { CatalogDraftSummary(versionId = it.versionId, name = it.label, base = it.base) }
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
        // What a draft is behind depends on the number it would be released as, measured on request.
        val behindNow = if (draft) GetCatalogDraft(catalog.tenantKey, catalog.id, requireNotNull(draftRef), withBehind = true).query()?.behind.orEmpty() else behind
        val dependencies = if (draft) {
            ListCatalogDependencies(catalog.tenantKey, catalog.id, catalogVersion = CatalogVersionRef(catalog.tenantKey, catalog.id, requireNotNull(draftRef))).query()
        } else {
            ListReleaseDependencies(catalog.tenantKey, catalog.id, requireNotNull(version)).query()
        }
        return CatalogVersionDto(
            version = version,
            versionId = versionId,
            name = if (draft) label else null,
            status = if (draft) CatalogVersionStatusDto.DRAFT else CatalogVersionStatusDto.RELEASED,
            behind = behindNow.map { release ->
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
        )
    }

    private fun CatalogDependencyDeclaration.toDependencyDto() = CatalogDependencyDto(
        registry = registry,
        namespace = namespace,
        catalogKey = catalogKey,
        releaseVersion = releaseVersion,
        versionId = draftId,
    )

    /** For a draft, the dependencies declared now, with their locks; for a release, its pins. */
    private fun dependenciesOf(at: ResolvedCatalogVersion): List<CatalogDependencyDeclaration> = if (at.isDraft) {
        ListCatalogDependencies(at.tenantKey, at.catalogKey, catalogVersion = at.ref).query()
    } else {
        ListReleaseDependencies(at.tenantKey, at.catalogKey, requireNotNull(at.version.version)).query()
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

                CatalogResourceState.NEW, CatalogResourceState.MODIFIED -> when {
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

    private val CatalogDependencyDeclaration.requirement get() = listOf(registry, namespace, catalogKey, releaseVersion, draftId)

    /**
     * A released version's base: the one it recorded, else (a release cut before releases recorded
     * it) the highest released version below it.
     */
    private fun baseOf(at: ResolvedCatalogVersion): String? {
        at.version.base?.let { return it }
        val own = at.version.version?.let(SemVer::parseOrNull) ?: return null
        return ListCatalogVersions(at.tenantKey, at.catalogKey, CatalogVersionStatus.RELEASED).query()
            .mapNotNull { version -> version.version?.let { number -> SemVer.parseOrNull(number)?.let { it to number } } }
            .filter { (semVer, _) -> semVer < own }
            .maxByOrNull { (semVer, _) -> semVer }
            ?.second
    }

    /** `…/versions/{draftId}`, from the request's own address. */
    private fun versionLocation(segment: String) = ServletUriComponentsBuilder.fromCurrentRequestUri().build().toUri().let { current ->
        current.resolve("${current.path.removeSuffix("/")}/$segment")
    }

    private fun CatalogVersionStatusDto.toDomain() = when (this) {
        CatalogVersionStatusDto.DRAFT -> CatalogVersionStatus.DRAFT
        CatalogVersionStatusDto.RELEASED -> CatalogVersionStatus.RELEASED
    }
}

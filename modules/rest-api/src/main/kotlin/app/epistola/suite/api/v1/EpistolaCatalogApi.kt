// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.CatalogsApi
import app.epistola.api.model.AvailableReleaseDependencyDto
import app.epistola.api.model.AvailableReleaseDto
import app.epistola.api.model.BehindReleaseDto
import app.epistola.api.model.CatalogChangesDto
import app.epistola.api.model.CatalogDependencyDto
import app.epistola.api.model.CatalogDependencyListResponse
import app.epistola.api.model.CatalogDraftMergePreview
import app.epistola.api.model.CatalogDraftMergeResource
import app.epistola.api.model.CatalogDraftReleasePreview
import app.epistola.api.model.CatalogDto
import app.epistola.api.model.CatalogListResponse
import app.epistola.api.model.CatalogMergeOutcome
import app.epistola.api.model.CatalogPresetDto
import app.epistola.api.model.CatalogReleaseDto
import app.epistola.api.model.CatalogReleaseListResponse
import app.epistola.api.model.CatalogReleaseStatus
import app.epistola.api.model.CatalogThemeReference
import app.epistola.api.model.CreateCatalogReleaseRequest
import app.epistola.api.model.ImportCatalogResponse
import app.epistola.api.model.InstallCatalogReleaseResponse
import app.epistola.api.model.MergeIntoCatalogDraftRequest
import app.epistola.api.model.MergeIntoCatalogDraftResponse
import app.epistola.api.model.ReleaseCatalogDraftRequest
import app.epistola.api.model.ReleaseCatalogDraftResponse
import app.epistola.api.model.ReleaseDependencyDto
import app.epistola.api.model.ReleasedChangeDto
import app.epistola.api.model.ReleasedDependencyDto
import app.epistola.api.model.ResourceChangeDto
import app.epistola.api.model.UpdateCatalogDependenciesRequest
import app.epistola.api.model.UpdateCatalogReleaseRequest
import app.epistola.suite.api.v1.shared.ETags
import app.epistola.suite.api.v1.shared.ListSorting
import app.epistola.suite.api.v1.shared.Pagination
import app.epistola.suite.api.v1.shared.catalogAt
import app.epistola.suite.api.v1.shared.catalogVersionAt
import app.epistola.suite.api.v1.shared.draftAt
import app.epistola.suite.api.v1.shared.draftRef
import app.epistola.suite.api.v1.shared.okWithETag
import app.epistola.suite.api.v1.shared.ref
import app.epistola.suite.api.v1.shared.tenantKey
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogIdentityProperties
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.commands.UpgradeCatalog
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.merge.MergeOutcome
import app.epistola.suite.catalog.merge.MergeReleaseIntoDraft
import app.epistola.suite.catalog.merge.MergeResolution
import app.epistola.suite.catalog.merge.PreviewDraftMerge
import app.epistola.suite.catalog.queries.CatalogEditability
import app.epistola.suite.catalog.queries.CatalogReadOnlyReason
import app.epistola.suite.catalog.queries.CatalogResourceChanges
import app.epistola.suite.catalog.queries.CatalogResourceState
import app.epistola.suite.catalog.queries.GetCatalogResourceChanges
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.queries.ListCatalogEditability
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.queries.ListReleaseDependencies
import app.epistola.suite.catalog.queries.PreviewCatalogUpgrade
import app.epistola.suite.catalog.sources.InstallCatalogRelease
import app.epistola.suite.catalog.sources.ListAvailableCatalogReleases
import app.epistola.suite.catalog.versions.AssessDraftNumber
import app.epistola.suite.catalog.versions.CatalogDefaultTheme
import app.epistola.suite.catalog.versions.CatalogPreset
import app.epistola.suite.catalog.versions.CatalogThemeSettings
import app.epistola.suite.catalog.versions.CatalogVersion
import app.epistola.suite.catalog.versions.CatalogVersionNotFoundException
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionStatus
import app.epistola.suite.catalog.versions.CreateCatalogDraft
import app.epistola.suite.catalog.versions.DeleteCatalogDraft
import app.epistola.suite.catalog.versions.GetCatalogDraft
import app.epistola.suite.catalog.versions.GetCatalogNames
import app.epistola.suite.catalog.versions.GetCatalogThemeSettings
import app.epistola.suite.catalog.versions.GetCatalogVersion
import app.epistola.suite.catalog.versions.GetReleasedThemeSettings
import app.epistola.suite.catalog.versions.ListCatalogVersions
import app.epistola.suite.catalog.versions.RenameCatalogDraft
import app.epistola.suite.catalog.versions.ResolvedCatalogVersion
import app.epistola.suite.catalog.versions.UpdateCatalogDraft
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import app.epistola.api.model.AvailableCatalogReleases as AvailableCatalogReleasesDto

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
        val editability = ListCatalogEditability(tenant).query()
        return ResponseEntity.ok(CatalogListResponse(page = slice.page, items = slice.items.map { it.toDto(editability.of(it.id)) }))
    }

    /**
     * Every release the catalog's source offers that the tenant lacks, older lines included, each
     * against the closest installed release below it (contract `listAvailableCatalogReleases`).
     */
    override fun listAvailableCatalogReleases(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
    ): ResponseEntity<AvailableCatalogReleasesDto> {
        val catalog = catalogAt(tenantKey, registry, namespace, catalogKey)
        val listed = ListAvailableCatalogReleases(catalog.tenantKey, catalog.id).query()
        return ResponseEntity.ok(
            AvailableCatalogReleasesDto(
                registry = listed.registry,
                namespace = listed.namespace,
                catalogKey = listed.catalogKey.value,
                available = listed.available.map { release ->
                    AvailableReleaseDto(
                        version = release.releaseVersion,
                        comparedTo = release.comparedTo,
                        changes = release.changes.map { ReleasedChangeDto(type = it.type, key = it.key, change = ReleasedChangeDto.Change.forValue(it.change.name.lowercase())) },
                        dependencies = release.dependencies.map { AvailableReleaseDependencyDto(registry = it.registry, namespace = it.namespace, catalogKey = it.catalogKey, version = it.releaseVersion, present = it.present) },
                    )
                },
            ),
        )
    }

    /** Installs release [releaseVersion] from the catalog's source, beside its others (contract `installCatalogRelease`). */
    override fun installCatalogRelease(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        version: String,
    ): ResponseEntity<InstallCatalogReleaseResponse> {
        val catalog = catalogAt(tenantKey, registry, namespace, catalogKey)
        val result = InstallCatalogRelease(catalog.tenantKey, catalog.id, version).execute()
        if (result.aborted) throw IllegalStateException("Installing ${catalog.id.value} $version failed; nothing changed")
        return ResponseEntity.ok(
            InstallCatalogReleaseResponse(
                registry = catalog.registry,
                namespace = catalog.namespace,
                catalogKey = catalog.id.value,
                version = result.releaseVersion ?: version,
                newlyInstalled = result.newlyInstalled,
                total = result.results.size,
                installedDependencies = result.installedDependencies,
                presentDependencies = result.presentDependencies,
            ),
        )
    }

    /**
     * Installs the release the archive carries, beside the catalog's others, in the catalog it names;
     * a catalog it creates is received (ADR 0032). Naming another registry or namespace is not
     * supported yet.
     */
    override fun importCatalog(
        tenantKey: String,
        file: MultipartFile,
        registry: String?,
        namespace: String?,
    ): ResponseEntity<ImportCatalogResponse> {
        if (registry != null) throw ApiOperationNotImplementedException("importCatalog.registry")
        if (namespace != null) throw ApiOperationNotImplementedException("importCatalog.namespace")
        val tenant = TenantKey.of(tenantKey)
        val result = ImportCatalogZip(
            tenantKey = tenant,
            zipBytes = file.bytes,
            // REST is non-interactive: an archive of an older wire version migrates without a prompt.
            confirmMigration = true,
        ).execute()
        if (result.aborted) throw IllegalStateException("Importing ${result.catalogKey.value} ${result.releaseVersion} failed; nothing changed")

        val name = GetCatalogNames(tenant, listOf(result.catalogKey)).query()[result.catalogKey]
        return ResponseEntity.ok(
            ImportCatalogResponse(
                registry = name?.registry ?: identity.defaultRegistry,
                namespace = name?.namespace ?: CatalogIdentityProperties.defaultNamespace(tenant),
                catalogKey = result.catalogKey.value,
                catalogName = result.catalogName,
                version = requireNotNull(result.releaseVersion),
                newlyInstalled = result.newlyInstalled,
                total = result.results.size,
                installedDependencies = result.installedDependencies,
                presentDependencies = result.presentDependencies,
            ),
        )
    }

    // ================== Releases (a draft is a release in status draft) ==================

    override fun listCatalogReleases(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        status: CatalogReleaseStatus?,
        page: Int,
        size: Int,
    ): ResponseEntity<CatalogReleaseListResponse> {
        val catalog = catalogAt(tenantKey, registry, namespace, catalogKey)
        // The versions come in SemVer order, which SQL does not sort by; a catalog's version count
        // is bounded by its release history, so the page is cut here.
        val versions = ListCatalogVersions(catalog.tenantKey, catalog.id, status?.toDomain()).query()
        val slice = Pagination.paginate(versions, page, size)
        return ResponseEntity.ok(CatalogReleaseListResponse(items = slice.items.map { it.toDto(catalog) }, page = slice.page))
    }

    override fun getCatalogRelease(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
    ): ResponseEntity<CatalogReleaseDto> {
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, release)
        return okWithETag(at.version.revision, at.version.toDto(at.catalog))
    }

    /** Starts a release in status draft, as a copy of the release `from` names, or of the highest released one. */
    override fun createCatalogRelease(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        createCatalogReleaseRequest: CreateCatalogReleaseRequest,
    ): ResponseEntity<CatalogReleaseDto> {
        val catalog = catalogAt(tenantKey, registry, namespace, catalogKey)
        val draft = CreateCatalogDraft(catalog.tenantKey, catalog.id, createCatalogReleaseRequest.name, createCatalogReleaseRequest.from).execute()
        return ResponseEntity.status(HttpStatus.CREATED)
            .location(versionLocation(draft.versionId))
            .body(draft.toDto(catalog))
    }

    /** Discards a draft. The next version, a convention of this suite, starts again on the latest release. */
    override fun deleteCatalogRelease(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
    ): ResponseEntity<Unit> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        DeleteCatalogDraft(draft.tenantKey, draft.catalogKey, draft.draftRef).execute()
        return ResponseEntity.noContent().build()
    }

    override fun updateCatalogRelease(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        updateCatalogReleaseRequest: UpdateCatalogReleaseRequest,
        ifMatch: String?,
    ): ResponseEntity<CatalogReleaseDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        val request = updateCatalogReleaseRequest
        // An omitted member keeps what the draft has; its theme settings are contract ADR 0007.
        UpdateCatalogDraft(
            tenantKey = draft.tenantKey,
            catalogKey = draft.catalogKey,
            draft = draft.draftRef,
            name = request.name,
            defaultTheme = request.defaultTheme?.let { theme ->
                CatalogDefaultTheme(ThemeKey.of(theme.key), theme.catalogKey?.let { app.epistola.suite.common.ids.CatalogKey.of(it) } ?: draft.catalogKey)
            },
            presets = request.presets?.map { CatalogPreset(it.key, it.name, it.description) },
            expectedRevision = ETags.expected(ifMatch),
        ).execute()
        val updated = catalogVersionAt(tenantKey, registry, namespace, catalogKey, release)
        return okWithETag(updated.version.revision, updated.version.toDto(updated.catalog))
    }

    /** The number releasing the draft would give it, from the kind of change against its base. */
    override fun previewCatalogDraftRelease(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
    ): ResponseEntity<CatalogDraftReleasePreview> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        val assessment = AssessDraftNumber(draft.tenantKey, draft.catalogKey, draft.draftRef).query()
            ?: throw CatalogVersionNotFoundException(draft.catalogKey, release)
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

    /** What merging release [version] into the draft [release] names would do (ADR 0032, alpha). */
    override fun previewCatalogDraftMerge(
        version: String,
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
    ): ResponseEntity<CatalogDraftMergePreview> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        val preview = PreviewDraftMerge(draft.tenantKey, draft.catalogKey, version, draft.ref).query()
        val quiet = setOf(MergeOutcome.UNCHANGED, MergeOutcome.SAME)
        return ResponseEntity.ok(
            CatalogDraftMergePreview(
                draft = draft.version.versionId,
                base = preview.base,
                release = preview.release,
                alreadyMerged = preview.alreadyMerged,
                resources = preview.resources.filter { it.outcome !in quiet }.map { resource ->
                    CatalogDraftMergeResource(
                        resourceType = resource.type,
                        resourceKey = resource.key,
                        name = resource.name,
                        outcome = CatalogMergeOutcome.forValue(resource.outcome.name.lowercase()),
                        draftChange = resource.ours?.let { CatalogDraftMergeResource.DraftChange.forValue(it.name.lowercase()) },
                        releaseChange = resource.theirs?.let { CatalogDraftMergeResource.ReleaseChange.forValue(it.name.lowercase()) },
                    )
                },
                unchanged = preview.resources.count { it.outcome in quiet },
            ),
        )
    }

    /** Merges a release into the draft [release] names (ADR 0032, alpha), resolving conflicts as asked. */
    override fun mergeIntoCatalogDraft(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        mergeIntoCatalogDraftRequest: MergeIntoCatalogDraftRequest,
    ): ResponseEntity<MergeIntoCatalogDraftResponse> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        val merged = MergeReleaseIntoDraft(
            tenantKey = draft.tenantKey,
            catalogKey = draft.catalogKey,
            release = mergeIntoCatalogDraftRequest.release,
            resolutions = mergeIntoCatalogDraftRequest.resolutions.orEmpty().mapValues { (_, side) -> MergeResolution.ofWire(side.name) ?: error("No resolution $side") },
            catalogVersion = draft.ref,
        ).execute()
        return ResponseEntity.ok(MergeIntoCatalogDraftResponse(merged.release, merged.taken, merged.removed, merged.kept))
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
        release: String,
        releaseCatalogDraftRequest: ReleaseCatalogDraftRequest,
    ): ResponseEntity<ReleaseCatalogDraftResponse> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
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
                release = released.toDto(draft.catalog),
                unchangedContent = result.unchangedContent,
                draftsBehind = result.draftsBehind,
                releasedDependencies = result.releasedDependencies.map {
                    ReleasedDependencyDto(registry = it.registry, namespace = it.namespace, catalogKey = it.catalogKey, releaseId = it.versionId, version = it.releaseVersion)
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
        release: String,
    ): ResponseEntity<CatalogChangesDto> {
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, release)
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
        release: String,
    ): ResponseEntity<CatalogChangesDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        return ResponseEntity.ok(MarkCatalogReady(draft.tenantKey, draft.catalogKey, catalogVersion = draft.ref).execute().toDto(draft))
    }

    // ================== Dependencies ==================

    override fun listCatalogDependencies(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
    ): ResponseEntity<CatalogDependencyListResponse> {
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, release)
        return okWithETag(at.version.revision, CatalogDependencyListResponse(items = dependenciesOf(at).map { it.toDependencyDto() }))
    }

    /** A dependency without a registry is on the default registry, as the contract says. */
    override fun updateCatalogDependencies(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        updateCatalogDependenciesRequest: UpdateCatalogDependenciesRequest,
        ifMatch: String?,
    ): ResponseEntity<CatalogDependencyListResponse> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        val declared = UpdateCatalogDependencies(
            tenantKey = draft.tenantKey,
            catalogKey = draft.catalogKey,
            dependencies = updateCatalogDependenciesRequest.dependencies.map {
                DeclaredCatalogDependency(
                    registry = it.registry ?: identity.defaultRegistry,
                    namespace = it.namespace,
                    catalogKey = it.catalogKey,
                    releaseVersion = it.version,
                    versionId = it.releaseId,
                )
            },
            catalogVersion = draft.ref,
            expectedRevision = ETags.expected(ifMatch),
        ).execute()
        val revision = catalogVersionAt(tenantKey, registry, namespace, catalogKey, release).version.revision
        return okWithETag(revision, CatalogDependencyListResponse(items = declared.map { it.toDependencyDto() }))
    }

    // ================== Helpers ==================

    private fun Catalog.toDto(editability: CatalogEditability?): CatalogDto {
        val reason = editability?.reason ?: if (editability == null) CatalogReadOnlyReason.SUPPLIED_BY_EPISTOLA.takeIf { suppliedByEpistola } else null
        return CatalogDto(
            registry = registry,
            namespace = namespace,
            catalogKey = id.value,
            name = name,
            description = description,
            editable = reason == null,
            readOnlyReason = reason?.let { CatalogDto.ReadOnlyReason.forValue(it.wire) },
            source = sourceUrl,
            highestVersion = highestReleaseVersion,
            fingerprint = highestReleaseFingerprint,
        )
    }

    /**
     * A version as the contract describes it. A draft lists the releases its declared dependencies
     * are locked to now; a release, the ones it was cut with. Which dependencies a release reaches
     * only through another is not recorded, so every one listed counts as direct. A draft lists the
     * releases it is behind, each with what it changed against the release below it.
     */
    private fun CatalogVersion.toDto(catalog: Catalog): CatalogReleaseDto {
        val draft = status == CatalogVersionStatus.DRAFT
        // What a draft is behind depends on the number it would be released as, measured on request.
        val behindNow = if (draft) GetCatalogDraft(catalog.tenantKey, catalog.id, requireNotNull(draftRef), withBehind = true).query()?.behind.orEmpty() else behind
        val dependencies = if (draft) {
            ListCatalogDependencies(catalog.tenantKey, catalog.id, catalogVersion = CatalogVersionRef(catalog.tenantKey, catalog.id, requireNotNull(draftRef))).query()
        } else {
            ListReleaseDependencies(catalog.tenantKey, catalog.id, requireNotNull(version)).query()
        }
        return CatalogReleaseDto(
            version = version,
            releaseId = versionId,
            name = if (draft) label else null,
            status = if (draft) CatalogReleaseStatus.DRAFT else CatalogReleaseStatus.RELEASED,
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
                        version = it,
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
            defaultTheme = themeSettings(catalog).defaultTheme?.let { theme ->
                CatalogThemeReference(key = theme.themeKey.value, catalogKey = theme.catalogKey.value.takeUnless { theme.catalogKey == catalog.id })
            },
            presets = themeSettings(catalog).presets.map { CatalogPresetDto(it.key, it.name, it.description) },
        )
    }

    /** The version's theme settings (contract ADR 0007): a draft's own, a release's as it froze them. */
    private fun CatalogVersion.themeSettings(catalog: Catalog): CatalogThemeSettings = if (status == CatalogVersionStatus.DRAFT) {
        GetCatalogThemeSettings(catalog.tenantKey, catalog.id, CatalogVersionRef(catalog.tenantKey, catalog.id, requireNotNull(draftRef))).query()
    } else {
        GetReleasedThemeSettings(catalog.tenantKey, catalog.id, requireNotNull(version)).query()
    }

    private fun CatalogDependencyDeclaration.toDependencyDto() = CatalogDependencyDto(
        registry = registry,
        namespace = namespace,
        catalogKey = catalogKey,
        version = releaseVersion,
        releaseId = draftId,
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

    private fun CatalogReleaseStatus.toDomain() = when (this) {
        CatalogReleaseStatus.DRAFT -> CatalogVersionStatus.DRAFT
        CatalogReleaseStatus.RELEASED -> CatalogVersionStatus.RELEASED
    }
}

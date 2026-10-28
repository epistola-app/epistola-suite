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
import app.epistola.api.model.CatalogDraftMergeSides
import app.epistola.api.model.CatalogDraftReleasePreview
import app.epistola.api.model.CatalogDto
import app.epistola.api.model.CatalogLevelChangeDto
import app.epistola.api.model.CatalogListResponse
import app.epistola.api.model.CatalogMergeBaseRef
import app.epistola.api.model.CatalogMergeOutcome
import app.epistola.api.model.CatalogPresetDto
import app.epistola.api.model.CatalogReleaseDto
import app.epistola.api.model.CatalogReleaseListResponse
import app.epistola.api.model.CatalogThemeReference
import app.epistola.api.model.CreateCatalogReleaseRequest
import app.epistola.api.model.ImportCatalogResponse
import app.epistola.api.model.InstallCatalogReleaseResponse
import app.epistola.api.model.MergeIntoCatalogDraftRequest
import app.epistola.api.model.MergeIntoCatalogDraftResponse
import app.epistola.api.model.PublishReleaseToRegistryRequest
import app.epistola.api.model.RegistryReleaseListResponse
import app.epistola.api.model.ReleaseCatalogDraftRequest
import app.epistola.api.model.ReleaseCatalogDraftResponse
import app.epistola.api.model.ReleaseDependencyDto
import app.epistola.api.model.ReleasePublicationDto
import app.epistola.api.model.ReleaseRegistryListResponse
import app.epistola.api.model.ReleasedChangeDto
import app.epistola.api.model.ReleasedDependencyDto
import app.epistola.api.model.ResourceChangeDto
import app.epistola.api.model.UpdateCatalogDependenciesRequest
import app.epistola.api.model.UpdateCatalogReleaseRequest
import app.epistola.suite.api.v1.shared.ETags
import app.epistola.suite.api.v1.shared.ListSorting
import app.epistola.suite.api.v1.shared.Pagination
import app.epistola.suite.api.v1.shared.catalogAt
import app.epistola.suite.api.v1.shared.catalogId
import app.epistola.suite.api.v1.shared.catalogVersionAt
import app.epistola.suite.api.v1.shared.draftAt
import app.epistola.suite.api.v1.shared.draftRef
import app.epistola.suite.api.v1.shared.okWithETag
import app.epistola.suite.api.v1.shared.ref
import app.epistola.suite.api.v1.shared.tenantKey
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogCanonicalizer
import app.epistola.suite.catalog.CatalogIdentityProperties
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.commands.UpgradeCatalog
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.merge.GetDraftMergeSides
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
import app.epistola.suite.catalog.queries.ListCatalogPage
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.queries.ListReleaseContentHashes
import app.epistola.suite.catalog.queries.ListReleaseDependencies
import app.epistola.suite.catalog.queries.PreviewCatalogUpgrade
import app.epistola.suite.catalog.queries.ReleaseContentHashes
import app.epistola.suite.catalog.releases.AssessDraftNumber
import app.epistola.suite.catalog.releases.CatalogDefaultTheme
import app.epistola.suite.catalog.releases.CatalogPreset
import app.epistola.suite.catalog.releases.CatalogRelease
import app.epistola.suite.catalog.releases.CatalogReleaseNotFoundException
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.catalog.releases.CatalogThemeSettings
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.catalog.releases.DeleteCatalogDraft
import app.epistola.suite.catalog.releases.GetCatalogDraft
import app.epistola.suite.catalog.releases.GetCatalogNames
import app.epistola.suite.catalog.releases.GetCatalogRelease
import app.epistola.suite.catalog.releases.GetCatalogThemeSettings
import app.epistola.suite.catalog.releases.GetReleasedThemeSettings
import app.epistola.suite.catalog.releases.ListCatalogReleasePage
import app.epistola.suite.catalog.releases.ListCatalogReleases
import app.epistola.suite.catalog.releases.ListReleasesByContentHash
import app.epistola.suite.catalog.releases.RenameCatalogDraft
import app.epistola.suite.catalog.releases.ResolvedCatalogRelease
import app.epistola.suite.catalog.releases.UpdateCatalogDraft
import app.epistola.suite.catalog.sources.InstallCatalogRelease
import app.epistola.suite.catalog.sources.ListAvailableCatalogReleases
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import app.epistola.api.model.AvailableCatalogReleases as AvailableCatalogReleasesDto
import app.epistola.api.model.CatalogReleaseStatus as ApiCatalogReleaseStatus

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

    private val registries = ReleaseRegistryEndpoints(identity)

    private companion object {
        /** How a request names the installation's default registry. */
        const val DEFAULT_REGISTRY_SEGMENT = "~"
    }

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
        val catalogs = ListCatalogPage(tenant, Pagination.requestOf(page, size)).query()
        val editability = ListCatalogEditability(tenant).query()
        val hashes = ListReleaseContentHashes(tenant).query()
        return ResponseEntity.ok(
            CatalogListResponse(
                page = Pagination.pageMeta(page, size, catalogs.total),
                items = catalogs.items.map { it.toDto(editability.of(it.catalogId), hashes) },
            ),
        )
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
        val listed = ListAvailableCatalogReleases(catalog.catalogId).query()
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
        val result = InstallCatalogRelease(catalog.catalogId, version).execute()
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
     * a catalog it creates is received (ADR 0032). An archive from before wire v8 names no catalog:
     * [registry] (`~` for the default) and [namespace] name it, and are required; for a v8 archive,
     * given, they must be what it names, since an import never renames.
     */
    override fun importCatalog(
        tenantKey: String,
        file: MultipartFile,
        registry: String?,
        namespace: String?,
    ): ResponseEntity<ImportCatalogResponse> {
        val tenant = TenantKey.of(tenantKey)
        val result = ImportCatalogZip(
            tenantKey = tenant,
            zipBytes = file.bytes,
            // REST is non-interactive: an archive of an older wire version migrates without a prompt.
            confirmMigration = true,
            registry = registry?.let { if (it == DEFAULT_REGISTRY_SEGMENT) identity.defaultRegistry else it },
            namespace = namespace,
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

    // ================== Release registries (contract ADR 0008, alpha) ==================

    override fun listReleaseRegistries(tenantKey: String): ResponseEntity<ReleaseRegistryListResponse> = ResponseEntity.ok(registries.list(tenantKey))

    override fun listRegistryReleases(tenantKey: String, name: String, registry: String, namespace: String, catalogKey: String): ResponseEntity<RegistryReleaseListResponse> = ResponseEntity.ok(registries.releases(tenantKey, name, registry, namespace, catalogKey))

    override fun installRegistryRelease(tenantKey: String, name: String, registry: String, namespace: String, catalogKey: String, version: String): ResponseEntity<ImportCatalogResponse> = ResponseEntity.ok(registries.install(tenantKey, name, registry, namespace, catalogKey, version))

    override fun publishReleaseToRegistry(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        publishReleaseToRegistryRequest: PublishReleaseToRegistryRequest,
    ): ResponseEntity<ReleasePublicationDto> = ResponseEntity.ok(registries.publish(tenantKey, registry, namespace, catalogKey, release, publishReleaseToRegistryRequest.to))

    // ================== Releases (a draft is a release in status draft) ==================

    override fun listCatalogReleases(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        status: ApiCatalogReleaseStatus?,
        contentHash: String?,
        page: Int,
        size: Int,
    ): ResponseEntity<CatalogReleaseListResponse> {
        val catalog = catalogAt(tenantKey, registry, namespace, catalogKey)
        if (contentHash != null) {
            // A release found by its content (ADR 0034): several numbers can name one content, and
            // none is an empty list. Filtered, ordered and paged in the database; a draft has no hash.
            val found = if (status == ApiCatalogReleaseStatus.DRAFT) {
                null
            } else {
                ListReleasesByContentHash(catalog.catalogId, contentHash, limit = Pagination.limitOf(size), offset = Pagination.offsetOf(page, size)).query()
            }
            return ResponseEntity.ok(
                CatalogReleaseListResponse(
                    items = found?.items.orEmpty().map { it.toDto(catalog) },
                    page = Pagination.pageMeta(page, size, found?.total?.toLong() ?: 0L),
                ),
            )
        }
        // Drafts first, then releases highest first: ordered and cut in the database.
        val versions = ListCatalogReleasePage(catalog.catalogId, status?.toDomain(), Pagination.requestOf(page, size)).query()
        return ResponseEntity.ok(CatalogReleaseListResponse(items = versions.items.map { it.toDto(catalog) }, page = Pagination.pageMeta(page, size, versions.total.toLong())))
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
        val draft = CreateCatalogDraft(catalog.catalogId, createCatalogReleaseRequest.name, createCatalogReleaseRequest.from).execute()
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
        DeleteCatalogDraft(draft.catalogId, draft.draftRef).execute()
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
        // An omitted or null member keeps what the draft has; its theme settings are contract ADR 0007.
        UpdateCatalogDraft(
            catalog = draft.catalogId,
            draft = draft.draftRef,
            name = request.name,
            defaultTheme = request.defaultTheme?.let { theme ->
                // The catalog its alias names, by the draft's declarations (ADR 0034, section 9).
                val themeCatalog = app.epistola.suite.catalog.CatalogReferences.resolve(draft.catalogId, theme.catalogKey, ListCatalogDependencies(draft.catalogId, catalogVersion = draft.ref).query())
                    ?: throw ValidationException("defaultTheme.catalogKey", "'${theme.catalogKey}' is not an alias this draft declares a dependency under", ValidationCode.CATALOG_DEPENDENCY_UNRESOLVED)
                CatalogDefaultTheme(ThemeKey.of(theme.key), themeCatalog)
            },
            presets = request.presets?.map { CatalogPreset(it.key, it.name, it.description) },
            expectedRevision = ETags.expected(ifMatch),
            // Null keeps the default theme; the flag removes it (and is refused beside a theme).
            clearDefaultTheme = request.clearDefaultTheme == true,
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
        val assessment = AssessDraftNumber(draft.catalogId, draft.draftRef).query()
            ?: throw CatalogReleaseNotFoundException(draft.catalogKey, release)
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
        val preview = PreviewDraftMerge(draft.catalogId, version, draft.ref).query()
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
                        needs = resource.needs,
                    )
                },
                unchanged = preview.resources.count { it.outcome in quiet },
                token = preview.token,
                missingBase = preview.missingBase?.let { CatalogMergeBaseRef(version = it.version, fingerprint = it.fingerprint, contentHash = it.fingerprint.takeIf(CatalogCanonicalizer::isContentHash)) },
            ),
        )
    }

    /** One entry of merging release [version] into the draft [release] names, on its three sides (contract ADR 0005, alpha). */
    override fun getCatalogDraftMergeSides(
        version: String,
        entry: String,
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
    ): ResponseEntity<CatalogDraftMergeSides> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        val sides = GetDraftMergeSides(draft.catalogId, version, entry, draft.ref).query()
        return ResponseEntity.ok(
            CatalogDraftMergeSides(
                entry = sides.entry,
                mergeBase = sides.mergeBase,
                token = sides.token,
                base = sides.base?.withoutNulls(),
                draft = sides.draft?.withoutNulls(),
                release = sides.release?.withoutNulls(),
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
            catalog = draft.catalogId,
            release = mergeIntoCatalogDraftRequest.release,
            resolutions = mergeIntoCatalogDraftRequest.resolutions.orEmpty().mapValues { (_, side) -> MergeResolution.ofWire(side.name) ?: error("No resolution $side") },
            catalogVersion = draft.ref,
            expectedToken = mergeIntoCatalogDraftRequest.expectedToken,
            mergedContent = mergeIntoCatalogDraftRequest.mergedContent.orEmpty(),
        ).execute()
        return ResponseEntity.ok(MergeIntoCatalogDraftResponse(merged.release, merged.taken, merged.removed, merged.kept, merged.merged))
    }

    /**
     * [this] side as the generated type holds it: a null member and an absent one mean the same
     * (`CatalogMergeEntryContent`), and the type has no room for a null value. Nested values are
     * kept as they are.
     */
    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.withoutNulls(): Map<String, Any> = filterValues { it != null } as Map<String, Any>

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
        val result = ReleaseCatalogDraft(
            catalog = draft.catalogId,
            version = releaseCatalogDraftRequest.version,
            notes = releaseCatalogDraftRequest.notes,
            acknowledgedBehind = releaseCatalogDraftRequest.acknowledgedBehind.orEmpty().toSet(),
            catalogVersion = draft.ref,
            releaseDependencies = releaseCatalogDraftRequest.releaseDependencies == true,
            // A draft with no change against its base is refused with 409 `conflict` (contract).
            requireChange = true,
        ).execute()
        val released = GetCatalogRelease(draft.catalogId, result.version).query()
            ?: throw CatalogReleaseNotFoundException(draft.catalogKey, result.version)
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
            return ResponseEntity.ok(CatalogChangesDto(releasable = false, dependenciesChanged = false, changes = emptyList(), catalogChanges = emptyList(), base = baseOf(at)))
        }
        return ResponseEntity.ok(GetCatalogResourceChanges(at.catalogId, catalogVersion = at.ref).query().toDto(at))
    }

    override fun markCatalogReady(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
    ): ResponseEntity<CatalogChangesDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        return ResponseEntity.ok(MarkCatalogReady(draft.catalogId, catalogVersion = draft.ref).execute().toDto(draft))
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
        val hashes = ListReleaseContentHashes(at.tenantKey).query()
        return okWithETag(at.version.revision, CatalogDependencyListResponse(items = dependenciesOf(at).map { it.toDependencyDto(hashes) }))
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
        // A declaration's alias is what content names the catalog by (contract ADR 0003). This
        // installation names every catalog a catalog uses by that catalog's key until catalogs are
        // told apart by namespace and key (ADR 0034, phase 4): another alias is refused, not ignored.
        updateCatalogDependenciesRequest.dependencies.forEachIndexed { index, requirement ->
            app.epistola.suite.validation.validate("dependencies[$index].alias", requirement.alias == null || requirement.alias == requirement.catalogKey, app.epistola.suite.validation.ValidationCode.CATALOG_DEPENDENCY_INVALID) {
                "This installation names every catalog a catalog uses by that catalog's key: alias '${requirement.alias}' for '${requirement.catalogKey}' " +
                    "is not supported until catalogs are told apart by namespace and key; leave it out, or give the catalog's key"
            }
        }
        val declared = UpdateCatalogDependencies(
            catalog = draft.catalogId,
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
        val hashes = ListReleaseContentHashes(draft.tenantKey).query()
        return okWithETag(revision, CatalogDependencyListResponse(items = declared.map { it.toDependencyDto(hashes) }))
    }

    // ================== Helpers ==================

    private fun Catalog.toDto(editability: CatalogEditability?, hashes: ReleaseContentHashes? = null): CatalogDto {
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
            contentHash = (hashes ?: ListReleaseContentHashes(tenantKey).query()).of(catalogId, highestReleaseVersion),
        )
    }

    /**
     * A version as the contract describes it. A draft lists the releases its declared dependencies
     * are locked to now; a release, the ones it was cut with. Which dependencies a release reaches
     * only through another is not recorded, so every one listed counts as direct. A draft lists the
     * releases it is behind, each with what it changed against the release below it.
     */
    private fun CatalogRelease.toDto(catalog: Catalog): CatalogReleaseDto {
        val draft = status == CatalogReleaseStatus.DRAFT
        // What a draft is behind depends on the number it would be released as, measured on request.
        val behindNow = if (draft) GetCatalogDraft(catalog.catalogId, requireNotNull(draftRef), withBehind = true).query()?.behind.orEmpty() else behind
        val dependencies = if (draft) {
            ListCatalogDependencies(catalog.catalogId, catalogVersion = CatalogReleaseRef(catalog.catalogId, requireNotNull(draftRef))).query()
        } else {
            ListReleaseDependencies(catalog.catalogId, requireNotNull(version)).query()
        }
        // Read only when a pin needs it: a list maps every release through here.
        val hashes by lazy { ListReleaseContentHashes(catalog.tenantKey).query() }
        return CatalogReleaseDto(
            version = version,
            releaseId = versionId,
            name = if (draft) label else null,
            status = if (draft) ApiCatalogReleaseStatus.DRAFT else ApiCatalogReleaseStatus.RELEASED,
            behind = behindNow.map { release ->
                BehindReleaseDto(
                    version = release.version,
                    changes = release.changes.map { ReleasedChangeDto(type = it.type, key = it.key, change = ReleasedChangeDto.Change.forValue(it.change)) },
                )
            },
            dependencies = dependencies.mapNotNull { dependency ->
                dependency.releaseVersion?.let {
                    ReleaseDependencyDto(
                        alias = dependency.alias,
                        registry = dependency.registry,
                        namespace = dependency.namespace,
                        catalogKey = dependency.catalogKey,
                        version = it,
                        direct = true,
                        contentHash = hashes.pinnedBy(dependency),
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
            // Its identity (ADR 0034); a draft has none.
            contentHash = contentHash.takeUnless { draft },
            defaultTheme = themeSettings(catalog).defaultTheme?.let { theme ->
                CatalogThemeReference(key = theme.themeKey.value, catalogKey = theme.catalogKey.value.takeUnless { theme.catalogKey == catalog.id })
            },
            presets = themeSettings(catalog).presets.map { CatalogPresetDto(it.key, it.name, it.description) },
        )
    }

    /** The version's theme settings (contract ADR 0007): a draft's own, a release's as it froze them. */
    private fun CatalogRelease.themeSettings(catalog: Catalog): CatalogThemeSettings = if (status == CatalogReleaseStatus.DRAFT) {
        GetCatalogThemeSettings(catalog.catalogId, CatalogReleaseRef(catalog.catalogId, requireNotNull(draftRef))).query()
    } else {
        GetReleasedThemeSettings(catalog.catalogId, requireNotNull(version)).query()
    }

    private fun CatalogDependencyDeclaration.toDependencyDto(hashes: ReleaseContentHashes) = CatalogDependencyDto(
        alias = alias,
        registry = registry,
        namespace = namespace,
        catalogKey = catalogKey,
        version = releaseVersion,
        releaseId = draftId,
        contentHash = hashes.pinnedBy(this),
    )

    /** For a draft, the dependencies declared now, with their locks; for a release, its pins. */
    private fun dependenciesOf(at: ResolvedCatalogRelease): List<CatalogDependencyDeclaration> = if (at.isDraft) {
        ListCatalogDependencies(at.catalogId, catalogVersion = at.ref).query()
    } else {
        ListReleaseDependencies(at.catalogId, requireNotNull(at.version.version)).query()
    }

    /**
     * A draft's changes against its base. A resource changed and marked ready is `ready`; one the last
     * release has unchanged is not listed. Beside the resources, what the content hash covers at the
     * catalog level (ADR 0034): each dependency declaration by its alias, the default theme, presets
     * and license. A draft is releasable when something changed against its base (a draft without one
     * always has), every changed resource is marked ready and no merged content awaits review.
     */
    private fun CatalogResourceChanges.toDto(draft: ResolvedCatalogRelease): CatalogChangesDto {
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
            change?.let { ResourceChangeDto(type = resource.type, key = resource.slug, change = it, mergedForReview = resource.mergedForReview) }
        }
        val catalogChanges = settings.map { setting ->
            val change = when (setting.state) {
                CatalogResourceState.NEW -> CatalogLevelChangeDto.Change.ADDED
                CatalogResourceState.REMOVED -> CatalogLevelChangeDto.Change.REMOVED
                else -> CatalogLevelChangeDto.Change.MODIFIED
            }
            CatalogLevelChangeDto(type = setting.type, key = setting.key, change = change)
        }
        val base = draft.version.base
        return CatalogChangesDto(
            releasable = (base == null || hasUnreleasedChanges) && notReady.isEmpty() && mergedForReview.isEmpty(),
            dependenciesChanged = settings.any { it.isDependency },
            changes = changes,
            catalogChanges = catalogChanges,
            base = base,
        )
    }

    /**
     * A released version's base: the one it recorded, else (a release cut before releases recorded
     * it) the highest released version below it.
     */
    private fun baseOf(at: ResolvedCatalogRelease): String? {
        at.version.base?.let { return it }
        val own = at.version.version?.let(SemVer::parseOrNull) ?: return null
        return ListCatalogReleases(at.catalogId, CatalogReleaseStatus.RELEASED).query()
            .mapNotNull { version -> version.version?.let { number -> SemVer.parseOrNull(number)?.let { it to number } } }
            .filter { (semVer, _) -> semVer < own }
            .maxByOrNull { (semVer, _) -> semVer }
            ?.second
    }

    /** `…/versions/{draftId}`, from the request's own address. */
    private fun versionLocation(segment: String) = ServletUriComponentsBuilder.fromCurrentRequestUri().build().toUri().let { current ->
        current.resolve("${current.path.removeSuffix("/")}/$segment")
    }

    private fun ApiCatalogReleaseStatus.toDomain() = when (this) {
        ApiCatalogReleaseStatus.DRAFT -> CatalogReleaseStatus.DRAFT
        ApiCatalogReleaseStatus.RELEASED -> CatalogReleaseStatus.RELEASED
    }
}

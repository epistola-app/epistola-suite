// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.catalog.protocol.AttributeAssignment
import app.epistola.catalog.protocol.CatalogLicense
import app.epistola.catalog.protocol.CatalogPresentation
import app.epistola.suite.assets.Asset
import app.epistola.suite.assets.AssetMediaCategory
import app.epistola.suite.assets.queries.ListAssets
import app.epistola.suite.attributes.queries.ListAttributeDefinitions
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CATALOG_SCHEMA_VERSION
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogMigrationConfirmationRequiredException
import app.epistola.suite.catalog.CatalogPublicationPolicy
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.commands.CatalogReleaseVersionException
import app.epistola.suite.catalog.commands.CatalogUpgradeConflictException
import app.epistola.suite.catalog.commands.ChangeCatalogNamespace
import app.epistola.suite.catalog.commands.CheckCatalogUpstream
import app.epistola.suite.catalog.commands.ContinueEverydayWork
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeleteCatalog
import app.epistola.suite.catalog.commands.DeleteCatalogRelease
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.ReleasePublication
import app.epistola.suite.catalog.commands.ReleaseRenderingDefaultsTooNewException
import app.epistola.suite.catalog.commands.SetCatalogPublicationSettings
import app.epistola.suite.catalog.commands.UpdateCatalogMetadata
import app.epistola.suite.catalog.dependencies.GetCatalogBorrowing
import app.epistola.suite.catalog.dependencies.ListCatalogDependents
import app.epistola.suite.catalog.migrations.CatalogSchemaException
import app.epistola.suite.catalog.migrations.CatalogSchemaTooNewException
import app.epistola.suite.catalog.migrations.CatalogSchemaTooOldException
import app.epistola.suite.catalog.migrations.CatalogSchemaUnknownException
import app.epistola.suite.catalog.namespaces.ListHeldNamespaces
import app.epistola.suite.catalog.namespaces.LocalAuthority
import app.epistola.suite.catalog.namespaces.NamespaceName
import app.epistola.suite.catalog.queries.BrowseCatalog
import app.epistola.suite.catalog.queries.CatalogReleaseSummary
import app.epistola.suite.catalog.queries.FindResourceUsages
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.GetCatalogDependencyGraph
import app.epistola.suite.catalog.queries.GetCatalogEditability
import app.epistola.suite.catalog.queries.GetCatalogReleaseDetail
import app.epistola.suite.catalog.queries.GetCatalogResourceChanges
import app.epistola.suite.catalog.queries.GetLatestCatalogRelease
import app.epistola.suite.catalog.queries.ListCatalogEditability
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.queries.ListReleasesWithSameContent
import app.epistola.suite.catalog.queries.PreviewInstall
import app.epistola.suite.catalog.queries.ResourceStatus
import app.epistola.suite.catalog.releases.CatalogDraft
import app.epistola.suite.catalog.releases.CatalogDraftUnchangedException
import app.epistola.suite.catalog.releases.CatalogReleaseBehindException
import app.epistola.suite.catalog.releases.CatalogReleaseIsReleasedException
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.releases.ListCatalogReleases
import app.epistola.suite.catalog.revisions.ReleaseInUseException
import app.epistola.suite.catalog.sources.InstallCatalogRelease
import app.epistola.suite.catalog.sources.ListAvailableCatalogReleases
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.EnvironmentNotFoundException
import app.epistola.suite.environments.EnvironmentDeploymentRefusedException
import app.epistola.suite.environments.ReleaseDependenciesMissingException
import app.epistola.suite.environments.ReleaseNotDeployableException
import app.epistola.suite.environments.commands.DeployCatalogRelease
import app.epistola.suite.environments.commands.UndeployCatalogRelease
import app.epistola.suite.environments.queries.ListDeployments
import app.epistola.suite.environments.queries.ListDraftDeployments
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.environments.queries.ListImplicitDeployments
import app.epistola.suite.exchange.CancelCatalogPublication
import app.epistola.suite.exchange.CatalogPublicationState
import app.epistola.suite.exchange.ExchangeSourceUri
import app.epistola.suite.exchange.GetCatalogPublicationState
import app.epistola.suite.exchange.GetExchangeCatalogLink
import app.epistola.suite.exchange.PublishCurrentCatalogRelease
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveFeatureToggles
import app.epistola.suite.handlers.CatalogViews.browseFragment
import app.epistola.suite.handlers.CatalogViews.catalogGraph
import app.epistola.suite.handlers.CatalogViews.catalogImages
import app.epistola.suite.handlers.CatalogViews.catalogListModel
import app.epistola.suite.handlers.CatalogViews.catalogPage
import app.epistola.suite.handlers.CatalogViews.catalogPageModel
import app.epistola.suite.handlers.CatalogViews.chooseNamespaceIfOffered
import app.epistola.suite.handlers.CatalogViews.deploymentRows
import app.epistola.suite.handlers.CatalogViews.deployments
import app.epistola.suite.handlers.CatalogViews.draftBar
import app.epistola.suite.handlers.CatalogViews.listWithError
import app.epistola.suite.handlers.CatalogViews.publicationPolicy
import app.epistola.suite.handlers.CatalogViews.refusedDialog
import app.epistola.suite.handlers.CatalogViews.releaseViews
import app.epistola.suite.handlers.CatalogViews.relocationEnabled
import app.epistola.suite.handlers.CatalogViews.section
import app.epistola.suite.htmx.CatalogSegment
import app.epistola.suite.htmx.HxSwap
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.catalog
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.catalogSegment
import app.epistola.suite.htmx.executeOrFormError
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.isHtmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.redirect
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.htmx.wantsFragmentResponse
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.requirePermission
import app.epistola.suite.validation.FieldLimits
import app.epistola.suite.validation.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.net.URI
import java.util.UUID

@Component
class CatalogHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    /** `GET /catalogs/graph`: which catalogs of the tenant use which, as a drawing. */
    fun graph(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requirePermission(tenantKey, Permission.CATALOG_VIEW)
        return ServerResponse.ok().page("catalogs/graph") {
            "pageTitle" to "Catalog dependencies - Epistola"
            "activeNavSection" to "catalogs"
            "tenantId" to tenantKey
            "graph" to catalogGraph(tenantKey, focus = null)
        }
    }

    /**
     * `GET /{catalogId}/browse`: the catalog page — the next version, every version, where each is
     * live (docs/catalog-workspace-navigation.md). It is about the catalog, not one version, so a
     * segment naming a version goes to that version's own page.
     */
    fun browse(request: ServerRequest): ServerResponse {
        val segment = request.catalogSegment()
        if (segment.draft != null) {
            return redirect(CatalogPages.draft(request.tenantId(), segment))
        }
        return browse(request, error = null)
    }

    /**
     * `GET /{catalogId}/draft`: a draft's own page — what is not published yet, its contents and its
     * details. A plain segment is the next version; `key@<draft id>` parallel work. A catalog that
     * cannot be changed here shows no drafts, and a catalog with nothing open goes to its catalog page, which offers to
     * start.
     */
    fun draftPage(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val segment = request.catalogSegment()
        val bar = draftBar(tenantId, segment)
        if (bar.chosen == null) return redirect(CatalogPages.browse(tenantId, segment))
        return catalogPage(request, "catalogs/draft", segment, bar, error = null, unusedAfterMerge = unusedAfterMerge(request, tenantId, segment, bar))
    }

    /**
     * After a merge (`?merged=<release>`), the catalogs this draft declares and no longer uses, which
     * the page offers to remove; null otherwise, and where the draft cannot be changed here.
     */
    private fun unusedAfterMerge(request: ServerRequest, tenantId: TenantId, segment: CatalogSegment, bar: CatalogDraftBar): UnusedAfterMerge? {
        val merged = request.param("merged").orElse(null)?.takeIf { it.isNotBlank() } ?: return null
        if (!bar.editable) return null
        val unused = GetCatalogBorrowing(segment.catalogIn(tenantId.key), segment.ref(tenantId.key)).query().unused
        return UnusedAfterMerge(merged, unused).takeIf { unused.isNotEmpty() }
    }

    /** `GET /{catalogId}/releases`: every release and draft of the catalog, each to its own page. */
    fun releasesPage(request: ServerRequest): ServerResponse = catalogSection(request, "catalogs/releases", catalogWide = true)

    /**
     * `GET /{catalogId}/versions`: where Releases was before releases were called releases. A
     * permanent redirect there, its query kept; an HTMX request is sent there by `HX-Location`, the
     * boosted navigation a link takes, since a redirect it follows would leave the old address shown.
     */
    fun formerVersionsPage(request: ServerRequest): ServerResponse {
        val query = request.uri().rawQuery?.let { "?$it" }.orEmpty()
        val target = CatalogPages.releases(request.catalog(request.tenantId())) + query
        if (request.wantsFragmentResponse) return request.htmx { dialogLocation(target) }
        return ServerResponse.status(HttpStatus.PERMANENT_REDIRECT).location(URI.create(target)).build()
    }

    /** `GET /{catalogId}/deployments`: what each environment serves of the catalog, and deploying another version. */
    fun deploymentsPage(request: ServerRequest): ServerResponse = catalogSection(request, "catalogs/deployments", catalogWide = true)

    /** `GET /{catalogId}/dependencies`: the catalogs this version depends on, how it stands with each, and the catalogs that depend on it. */
    fun dependenciesPage(request: ServerRequest): ServerResponse = catalogSection(request, "catalogs/dependencies", catalogWide = false)

    /** `GET /{catalogId}/settings`: what this version goes out with (name, description, presentation), its key, and Exchange. */
    fun settingsPage(request: ServerRequest): ServerResponse = catalogSection(request, "catalogs/settings", catalogWide = false)

    /**
     * One of a catalog's own pages (docs/workspace-shell.md). A [catalogWide] page is the same for
     * every version, so it is shown at the catalog; the others at the version the segment names.
     */
    private fun catalogSection(request: ServerRequest, template: String, catalogWide: Boolean): ServerResponse {
        val tenantId = request.tenantId()
        val segment = request.catalogSegment().let { if (catalogWide) it.at(null) else it }
        val bar = draftBar(tenantId, segment)
        return catalogPage(request, template, segment, bar, error = null)
    }

    /**
     * `GET /{catalogId}/bar-status`: the context bar's status for the version the segment names —
     * what is not published yet, and Publish — loaded after the page, because working it out builds
     * the catalog.
     */
    fun barStatus(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val segment = request.catalogSegment()
        val bar = draftBar(tenantId, segment)
        val readiness = if (bar.editable && bar.chosen != null) CatalogReleaseReadiness.of(bar) else null
        return request.htmx {
            fragment("catalogs/workspace", "bar-status") {
                "tenantId" to tenantId.key
                "bar" to bar
                "readiness" to readiness
            }
            onFullPage { redirect(CatalogPages.browse(tenantId, segment)) }
        }
    }

    /**
     * The page a draft action refused on, with [message] shown: the draft's own page when [segment]
     * names an open draft, such as discarding one that alone holds the tenant's default theme, else
     * the catalog page.
     */
    fun browseWithError(request: ServerRequest, segment: CatalogSegment, message: String): ServerResponse {
        val tenantId = request.tenantId()
        val bar = draftBar(tenantId, segment)
        return if (bar.chosen != null) {
            catalogPage(request, "catalogs/draft", segment, bar, error = null, draftError = message)
        } else {
            catalogPage(request, "catalogs/browse", segment.at(null), draftBar(tenantId, segment.at(null)), error = null, draftError = message)
        }
    }

    private fun browse(
        request: ServerRequest,
        error: String?,
        deployRefusal: DeployRefusalView? = null,
    ): ServerResponse {
        val tenantId = request.tenantId()
        val segment = request.catalogSegment().at(null)
        return catalogPage(request, "catalogs/browse", segment, draftBar(tenantId, segment), error, deployRefusal)
    }

    data class CatalogMetadataAttributeField(val descriptor: AttributeDescriptor, val value: String?)

    data class CatalogPresentationAssetView(val slug: String, val asset: Asset?) {
        val available: Boolean get() = asset != null
    }

    /** An environment, and the releases of the catalog on screen it serves, highest first (empty when none). */
    data class DeploymentRowView(
        val environmentKey: String,
        val environmentName: String,
        val production: Boolean = false,
        val served: List<ServedReleaseView>,
        val implicit: List<ImplicitUseView> = emptyList(),
        /** What it uses instead of what is deployed (a followed draft, a pinned version); null when nothing. */
        val uses: String? = null,
        /** The draft it follows, when [uses] is one. */
        val followsDraft: String? = null,
    ) {
        val servedVersions: Set<String> get() = served.mapTo(HashSet()) { it.version }

        /** The version an application gets there unless it asks for another: the highest it serves. */
        val inUse: String? get() = served.firstOrNull()?.version

        /** The environments following the draft [draftId]. */
        fun follows(draftId: String?): Boolean = draftId != null && followsDraft == draftId
    }

    /** A version of this catalog an environment uses because [neededBy] (catalog names) depend on it. */
    data class ImplicitUseView(val version: String, val neededBy: List<String>)

    /** One release an environment serves, and since when. */
    data class ServedReleaseView(
        val version: String,
        val deployedAt: java.time.OffsetDateTime,
        /** Generation requests through the environment accepted for it since it was deployed. */
        val documentsRequested: Long = 0,
        /** When the last was; null when none was. */
        val lastUsedAt: java.time.OffsetDateTime? = null,
    ) {
        /** How much it is used, as people read it. */
        val usage: String get() = DeploymentUsageText.of(documentsRequested, lastUsedAt)
    }

    /** One release as the catalog page shows it: the release, and what may be done with it. */
    data class CatalogReleaseView(
        val release: CatalogReleaseSummary,
        /** Already sent to Exchange, so the row says so instead of offering to send it again. */
        val published: Boolean,
        /** Publishing this release would be accepted — the command decides the same way. */
        val publishable: Boolean,
    )
}

/** The catalogs a draft no longer uses after merging [release], which its page asks whether to remove. */
data class UnusedAfterMerge(val release: String, val catalogs: List<app.epistola.suite.catalog.dependencies.BorrowedCatalog>)

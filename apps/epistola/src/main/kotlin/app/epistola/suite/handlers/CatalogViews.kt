// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.assets.AssetMediaCategory
import app.epistola.suite.assets.queries.ListAssets
import app.epistola.suite.catalog.CatalogPublicationPolicy
import app.epistola.suite.catalog.commands.ChangeCatalogNamespace
import app.epistola.suite.catalog.dependencies.ListCatalogDependents
import app.epistola.suite.catalog.queries.BrowseCatalog
import app.epistola.suite.catalog.queries.CatalogReleaseSummary
import app.epistola.suite.catalog.queries.FindResourceUsages
import app.epistola.suite.catalog.queries.GetCatalogDependencyGraph
import app.epistola.suite.catalog.queries.ListCatalogEditability
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.queries.ResourceStatus
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.catalog.releases.ListCatalogReleases
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.environments.queries.ListDeployments
import app.epistola.suite.environments.queries.ListDraftDeployments
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.environments.queries.ListImplicitDeployments
import app.epistola.suite.exchange.CatalogPublicationState
import app.epistola.suite.exchange.GetCatalogPublicationState
import app.epistola.suite.exchange.GetExchangeCatalogLink
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveFeatureToggles
import app.epistola.suite.handlers.CatalogHandler.CatalogPresentationAssetView
import app.epistola.suite.handlers.CatalogHandler.CatalogReleaseView
import app.epistola.suite.handlers.CatalogHandler.DeploymentRowView
import app.epistola.suite.handlers.CatalogHandler.ImplicitUseView
import app.epistola.suite.handlers.CatalogHandler.ServedReleaseView
import app.epistola.suite.htmx.CatalogSegment
import app.epistola.suite.htmx.HxSwap
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.catalog
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.catalogSegment
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.redirect
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.validation.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * What the catalog handlers render alike: the catalog page and its sections, the draft bar, the
 * catalogs list model and the release dialogs' shared parts.
 */
internal object CatalogViews {
    // Logs under CatalogHandler, the name these lines had before the handler was split.
    private val logger = LoggerFactory.getLogger(CatalogHandler::class.java)

    /** The dependency drawing; each catalog links to its own Dependencies page, which narrows the drawing to it. */
    internal fun catalogGraph(tenantKey: TenantKey, focus: String?): CatalogGraphView = CatalogGraphView.of(
        GetCatalogDependencyGraph(tenantKey).query(),
        focus = focus,
        readOnlyLabel = CatalogGraphView.readOnlyLabels(ListCatalogEditability(tenantKey).query()),
    ) { key -> "/tenants/${tenantKey.value}/catalogs/$key/dependencies" }

    /**
     * Applies a namespace picked on a form: the catalog's own (contract ADR 0003), which is also where
     * it publishes. Offered only while it can change, and only granted ones.
     */
    internal fun chooseNamespaceIfOffered(request: ServerRequest, catalog: app.epistola.suite.common.ids.CatalogId) {
        request.params().getFirst("chosenNamespace")?.trim()?.ifBlank { null }?.let { namespace ->
            ChangeCatalogNamespace(catalog, namespace).execute()
        }
    }

    /** Rejects an unknown policy as a form error instead of letting `valueOf` become a 500. */
    internal fun publicationPolicy(raw: String?): CatalogPublicationPolicy = CatalogPublicationPolicy.entries.firstOrNull { it.name == raw }
        ?: throw ValidationException("publicationPolicy", "Choose a valid publication policy.")

    /** One row per environment of the tenant, with every release of this catalog it serves, highest first. */
    internal fun deploymentRows(catalog: app.epistola.suite.common.ids.CatalogId): List<DeploymentRowView> {
        val tenantKey = catalog.tenantKey
        val served = ListDeployments(tenantKey, catalog).query().filter { !it.isDraft }.groupBy { it.environmentKey }
        // A draft it serves, which a request naming no version renders.
        val drafts = ListDraftDeployments(tenantKey, catalog = catalog).query().associateBy { it.environmentKey }
        // By namespace and key: two catalogs can share a key (ADR 0034).
        val catalogs by lazy { ListCatalogs(tenantKey).query() }
        val names by lazy { catalogs.associate { it.catalogId to it.name } }
        val own = catalog
        return ListEnvironments(tenantId = app.epistola.suite.common.ids.TenantId(tenantKey)).query().map { environment ->
            DeploymentRowView(
                environmentKey = environment.id.value,
                environmentName = environment.name,
                production = environment.production,
                served = served[environment.id].orEmpty().map { ServedReleaseView(requireNotNull(it.version), it.deployedAt, it.documentsRequested, it.lastUsedAt) },
                // Not deployed itself, but used there because a deployed catalog depends on it.
                implicit = ListImplicitDeployments(tenantKey, environment.id).query().filter { it.catalog == own }
                    .map { ImplicitUseView(it.version, it.neededBy.map { catalog -> names[catalog] ?: catalog.key.value }) },
                uses = drafts[environment.id]?.draftLabel,
                followsDraft = drafts[environment.id]?.draftId,
            )
        }
    }

    /**
     * A catalog dialog shown again with why its action was refused: a 422 (so nothing treats the
     * refusal as done), swapped into its container as the dialog's form asks.
     */
    internal fun refusedDialog(request: ServerRequest, fragment: String, model: Map<String, Any?>): ServerResponse = request.htmx {
        fragment("catalogs/list", fragment) { putAll(model) }
        reswap(HxSwap.INNER_HTML)
        status(422)
        onFullPage { redirect(CatalogPages.browse(request.tenantId(), request.catalogSegment())) }
    }

    /** The Deployments page with [error] or a refused deploy shown, for a deploy that did not go through. */
    internal fun deployments(request: ServerRequest, error: String?, deployRefusal: DeployRefusalView? = null): ServerResponse = section(request, "catalogs/deployments", error, deployRefusal)

    /**
     * One of the catalog's own pages ([template]) with [error] shown: where an action taken there
     * that did not go through lands, with the reason.
     */
    internal fun section(request: ServerRequest, template: String, error: String?, deployRefusal: DeployRefusalView? = null): ServerResponse {
        val tenantId = request.tenantId()
        val segment = request.catalogSegment().at(null)
        return catalogPage(request, template, segment, draftBar(tenantId, segment), error, deployRefusal)
    }

    internal fun draftBar(tenantId: TenantId, segment: CatalogSegment): CatalogDraftBar = CatalogDraftBar.of(tenantId.key, segment) { CatalogPages.draft(tenantId, it) }

    /** The catalog page or a draft's page ([template]): both show the catalog at [segment]. */
    internal fun catalogPage(
        request: ServerRequest,
        template: String,
        segment: CatalogSegment,
        drafts: CatalogDraftBar,
        error: String?,
        deployRefusal: DeployRefusalView? = null,
        draftError: String? = null,
        unusedAfterMerge: UnusedAfterMerge? = null,
    ): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = segment.key
        val catalog = segment.catalogIn(request.tenantId())
        val version = drafts.ref

        return try {
            val result = BrowseCatalog(catalog = catalog, catalogVersion = version).query()
            val usages = FindResourceUsages(catalog = catalog, catalogVersion = version).query()
            val usageCounts = usages.mapValues { it.value.size }
            val images = catalogImages(catalog, version)
            val imagesBySlug = images.associateBy { it.id.value }
            // Releases are cut, and so published, only where the catalog can be changed.
            val publication = if (drafts.editable) {
                GetCatalogPublicationState(catalog).query()
            } else {
                null
            }
            // The releases cut here and the ones received (each install records its release), side
            // by side. Any of them can be deployed.
            val releases = ListCatalogReleaseSummaries(catalog).query()
            val deployments = deploymentRows(catalog)
            // Every release can be deployed. Newest first, as the list is.
            val deployableVersions = releases.map { it.version }
            // The open drafts an environment can follow (not production): the next version and parallel work.
            val followableDrafts = if (drafts.editable) {
                ListCatalogReleases(catalog).query().filter { it.status == CatalogReleaseStatus.DRAFT }
            } else {
                emptyList()
            }
            val releaseViews = releaseViews(releases, publication)
            // The catalog's page on Exchange, when it came from there. Null for a plain URL
            // source, a ZIP import, or a catalog only released here — the view falls back to the text
            // it rendered before. Resolved once per page: the rows append to it themselves.
            val exchangeCatalogUrl = GetExchangeCatalogLink(tenantId.key, result.catalog.sourceUrl).query()
            // Installing from a source installs the whole manifest (#850), so the action means
            // something only while the manifest holds something this installation does not.
            // Gated on that rather than on the catalog having a source, which was always true and
            // left the button offering to install what was already installed.
            val hasUninstalledResources = result.resources.any { it.status == ResourceStatus.AVAILABLE }
            // Only a catalog that can be changed here has a next release to prepare.
            val editable = drafts.editable
            val readiness = if (editable) CatalogReleaseReadiness.of(drafts) else null
            val auth = AuthContext.from(SecurityContext.current(), tenantId.key)
            // From a new catalog to an application generating from it; until something is live.
            val gettingStarted = if (editable) {
                CatalogGettingStarted(
                    namespace = result.catalog.namespace,
                    catalogKey = catalogKey.value,
                    templateKey = result.resources.firstOrNull { it.type == "template" }?.slug,
                    published = releases.isNotEmpty(),
                    environmentKeys = deployments.map { it.environmentKey },
                    liveIn = deployments.filter { it.served.isNotEmpty() }.map { it.environmentKey },
                    mayEdit = auth.has(Permission.TEMPLATE_EDIT),
                    mayPublish = auth.has(Permission.CATALOG_RELEASE),
                    mayDeploy = auth.has(Permission.ENVIRONMENT_DEPLOY),
                )
            } else {
                null
            }

            // Inside the model builder `to` means a model entry, so the map is built out here.
            val changeStates = readiness?.changes?.resources.orEmpty().associateBy({ "${it.type}/${it.slug}" }, { it.state })
            // Only the Dependencies page says who depends on this catalog; the others skip the lookup.
            val dependents = if (template == "catalogs/dependencies") ListCatalogDependents(catalog).query() else emptyList()
            val editsDependencies = editable && drafts.chosen != null
            ServerResponse.ok().page(template) {
                "pageTitle" to when (template) {
                    "catalogs/draft" -> "${drafts.draftTitle} - ${result.catalog.name} - Epistola"
                    "catalogs/releases" -> "Releases - ${result.catalog.name} - Epistola"
                    "catalogs/deployments" -> "Deployments - ${result.catalog.name} - Epistola"
                    "catalogs/dependencies" -> "Dependencies - ${result.catalog.name} - Epistola"
                    "catalogs/settings" -> "Settings - ${result.catalog.name} - Epistola"
                    else -> "${result.catalog.name} - Catalog - Epistola"
                }
                "tenantId" to tenantId.key
                "activeNavSection" to "catalogs"
                "catalog" to result.catalog
                // The catalog segment every per-catalog link on the page carries (a draft, or plain).
                "catalogId" to segment.value
                "draftBar" to drafts
                "readiness" to readiness
                "gettingStarted" to gettingStarted
                "dependents" to dependents
                if (template == "catalogs/dependencies") "dependencyGraph" to catalogGraph(tenantId.key, focus = "${result.catalog.namespace}/${catalogKey.value}")
                "mayEditDependencies" to (editsDependencies && auth.has(Permission.TEMPLATE_EDIT))
                "dependenciesWithheld" to (editsDependencies && !auth.has(Permission.TEMPLATE_EDIT))
                // What the version holds, by kind, with a way to add each kind there; nothing is
                // added to a catalog that cannot be changed here.
                "contents" to CatalogContents.of(
                    tenantId.key,
                    segment,
                    result.resources,
                    changeStates,
                ) { editable && auth.has(it) }
                if (draftError != null) "nextReleaseError" to draftError
                "exchangeCatalogUrl" to exchangeCatalogUrl
                "publication" to publication
                "releases" to releaseViews
                "deployments" to deployments
                "deployableVersions" to deployableVersions
                "followableDrafts" to followableDrafts
                "publicationError" to error
                "deployRefusal" to deployRefusal
                "unusedAfterMerge" to unusedAfterMerge
                "resources" to result.resources
                "hasUninstalledResources" to hasUninstalledResources
                "usageCounts" to usageCounts
                "hasImages" to images.isNotEmpty()
                "keywords" to result.catalog.catalogMetadata.keywords.inDisplayOrder()
                "presentationImages" to result.catalog.catalogMetadata.presentation?.imageKeys.orEmpty().map {
                    CatalogPresentationAssetView(it, imagesBySlug[it])
                }
                "presentationIcon" to result.catalog.catalogMetadata.presentation?.iconImageKey?.let {
                    CatalogPresentationAssetView(it, imagesBySlug[it])
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to browse catalog: ${e.message}", e)
            listWithError(request, "Failed to fetch catalog. The remote server may be unavailable or the URL may be incorrect.")
        }
    }

    /**
     * Each release with its affordances already decided. The template gates on one name apiece rather
     * than assembling the conjunction itself -- the catalog being able to publish at all, this release
     * having content to send, and it not having been sent are three facts from three places, and a
     * screen that ANDs them is a rule with no home.
     */
    internal fun releaseViews(releases: List<CatalogReleaseSummary>, publication: CatalogPublicationState?): List<CatalogReleaseView> {
        val publishedVersions = publication?.publications.orEmpty().mapTo(HashSet()) { it.version }
        return releases.map { release ->
            CatalogReleaseView(
                release = release,
                published = release.version in publishedVersions,
                publishable = publication != null &&
                    publication.canPublish &&
                    publication.hasPublishableDestination &&
                    release.version !in publishedVersions,
            )
        }
    }

    /**
     * The images this catalog can present. The presentation controls are offered only when this is
     * non-empty (.agents/rules/ui-affordances.md), so every model that renders the metadata card
     * has to agree on it — hence one definition rather than the three it replaced.
     */
    internal fun catalogImages(
        catalog: app.epistola.suite.common.ids.CatalogId,
        version: app.epistola.suite.catalog.releases.CatalogReleaseRef? = null,
    ) = ListAssets(catalog.tenantKey, catalog = catalog, catalogVersion = version).query()
        .filter { it.mediaType.category == AssetMediaCategory.IMAGE }

    internal fun browseFragment(
        request: ServerRequest,
        catalog: app.epistola.suite.common.ids.CatalogId,
        successMessage: String? = null,
        errorMessage: String? = null,
    ): ServerResponse {
        val tenantId = request.tenantId()
        val result = BrowseCatalog(catalog = catalog).query()
        val images = catalogImages(catalog)
        val imagesBySlug = images.associateBy { it.id.value }

        return request.htmx {
            fragment("catalogs/content", "resource-rows") {
                "resources" to result.resources
                "tenantId" to tenantId.key
                "catalog" to result.catalog
            }
            oob("catalogs/browse", "alert") {
                if (successMessage != null) "successMessage" to successMessage
                if (errorMessage != null) "errorMessage" to errorMessage
            }
            oob("catalogs/content", "metadata-card") {
                "tenantId" to tenantId.key
                "catalog" to result.catalog
                "hasImages" to images.isNotEmpty()
                "keywords" to result.catalog.catalogMetadata.keywords.inDisplayOrder()
                "presentationImages" to result.catalog.catalogMetadata.presentation?.imageKeys.orEmpty().map {
                    CatalogPresentationAssetView(it, imagesBySlug[it])
                }
                "presentationIcon" to result.catalog.catalogMetadata.presentation?.iconImageKey?.let {
                    CatalogPresentationAssetView(it, imagesBySlug[it])
                }
            }
            trigger("installComplete")
            onFullPage {
                page("catalogs/browse") {
                    "pageTitle" to "${result.catalog.name} - Catalog - Epistola"
                    "tenantId" to tenantId.key
                    "activeNavSection" to "catalogs"
                    "catalog" to result.catalog
                    "resources" to result.resources
                    "hasImages" to images.isNotEmpty()
                    "keywords" to result.catalog.catalogMetadata.keywords.inDisplayOrder()
                    "presentationImages" to result.catalog.catalogMetadata.presentation?.imageKeys.orEmpty().map {
                        CatalogPresentationAssetView(it, imagesBySlug[it])
                    }
                    "presentationIcon" to result.catalog.catalogMetadata.presentation?.iconImageKey?.let {
                        CatalogPresentationAssetView(it, imagesBySlug[it])
                    }
                    if (successMessage != null) "successMessage" to successMessage
                    if (errorMessage != null) "error" to errorMessage
                }
            }
        }
    }

    /**
     * The catalog list as every full-page render of it needs it: the list, plus whether the
     * Organise action in its header is available. The create, register and import dialogs render
     * the list page behind them, and used to lose that action there.
     */
    internal fun ModelBuilder.catalogListModel(request: ServerRequest) {
        catalogListModel(request.tenantId().key)
        "resourceRelocationEnabled" to request.relocationEnabled()
    }

    /**
     * The full-page list model used by the newForm / createCatalog non-HTMX
     * branches so the list renders behind the embedded create dialog. Adds the
     * page chrome the host list page needs on top of [catalogListModel].
     */
    internal fun ModelBuilder.catalogPageModel(request: ServerRequest) {
        "pageTitle" to "Catalogs - Epistola"
        "activeNavSection" to "catalogs"
        catalogListModel(request)
    }

    internal fun listWithError(request: ServerRequest, error: String): ServerResponse = ServerResponse.ok().page("catalogs/list") {
        "pageTitle" to "Catalogs - Epistola"
        "activeNavSection" to "catalogs"
        catalogListModel(request)
        "error" to error
    }

    /**
     * Whether to offer the Organise entry point. Alpha, so absent unless the tenant enables it.
     */
    internal fun ServerRequest.relocationEnabled() = ResolveFeatureToggles(tenantId().key).query()[KnownFeatures.RESOURCE_RELOCATION] == true
}

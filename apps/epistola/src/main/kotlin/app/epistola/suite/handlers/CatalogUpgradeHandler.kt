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

/** Upgrading a subscribed catalog to a newer release of its source. */
@Component
class CatalogUpgradeHandler {
    // Logs under CatalogHandler, the name these lines had before the handler was split.
    private val logger = LoggerFactory.getLogger(CatalogHandler::class.java)

    /**
     * The per-row "check for updates" button.
     *
     * Asks the source now and records the answer, so the next page load already knows it and the
     * background check does not immediately re-ask. Returns the same fragment the list renders from,
     * which is what keeps one definition of the Version cell.
     */
    fun upgradeCheck(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalog(request.tenantId())
        val catalogKey = catalogId.key
        val catalog = GetCatalog(catalogId).query()
            ?: return fragmentOrPage(request, "catalogs/list", "version-status", mapOf("tenantId" to tenantId.key, "catalogId" to catalogId.address, "status" to null))

        val check = try {
            CheckCatalogUpstream(catalogId).execute()
        } catch (e: Exception) {
            logger.warn("Upgrade check failed for catalog {}: {}", catalogKey, e.message)
            null
        }
        val status = CatalogReleaseStatusView.of(
            catalog,
            check,
            CATALOG_SCHEMA_VERSION,
            fromExchange = ExchangeSourceUri.matches(catalog.sourceUrl),
        )
        return fragmentOrPage(
            request,
            "catalogs/list",
            "version-status",
            mapOf(
                "tenantId" to tenantId.key,
                "catalogId" to catalogId.address,
                "status" to status,
            ),
        )
    }

    private fun upgradeDialog(request: ServerRequest, catalog: app.epistola.suite.common.ids.CatalogId, error: String? = null, refused: Boolean = false): ServerResponse {
        val tenantId = catalog.tenantId
        val catalogKey = catalog.key
        val model = HashMap<String, Any?>()
        model["tenantId"] = tenantId.key
        model["catalogId"] = catalogKey.value
        model["error"] = error
        try {
            model["available"] = ListAvailableCatalogReleases(catalog).query()
        } catch (e: Exception) {
            logger.warn("Listing the releases available for catalog {} failed: {}", catalogKey, e.message)
            model["available"] = null
            if (error == null) model["error"] = e.message ?: "The catalog's source could not be asked. It may be unavailable."
        }
        // A refused upgrade says so with a 422; the preview is a plain 200.
        if (refused) return refusedDialog(request, "upgrade-dialog", model)
        return fragmentOrPage(request, "catalogs/list", "upgrade-dialog", model)
    }

    fun upgradePreview(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalog = request.catalog(tenantId)
        return upgradeDialog(request, catalog)
    }

    fun upgrade(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalog = request.catalog(tenantId)

        val releaseVersion = request.param("releaseVersion").orElse("").trim().ifEmpty { null }
            ?: return upgradeDialog(request, catalog, "Choose a release to install.", refused = true)

        return try {
            val result = InstallCatalogRelease(catalog, releaseVersion).execute()

            if (result.aborted) {
                val failed = result.results
                    .filter { it.status == InstallStatus.FAILED }
                    .joinToString(", ") { "${it.type}/${it.slug}" }
                return upgradeDialog(request, catalog, "Installing v$releaseVersion stopped: these resources failed to install, and nothing was changed: $failed", refused = true)
            }

            request.htmx {
                fragment("catalogs/list", "upgrade-done") {}
                oob("catalogs/list", "catalog-list") {
                    catalogListModel(request)
                    "oob" to true
                }
                onFullPage {
                    redirect("/tenants/${tenantId.key}/catalogs?saved=true")
                }
            }
        } catch (e: CatalogUpgradeConflictException) {
            upgradeDialog(request, catalog, e.message ?: "Installing it is blocked: resources are still in use.", refused = true)
        } catch (e: Exception) {
            logger.warn("Failed to install a release of catalog: ${e.message}", e)
            upgradeDialog(request, catalog, e.message ?: "Failed to install the release.", refused = true)
        }
    }
}

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

/** Releasing a catalog's draft, a release's own page, and deleting or publishing a release. */
@Component
class CatalogReleaseActionsHandler {
    // Logs under CatalogHandler, the name these lines had before the handler was split.
    private val logger = LoggerFactory.getLogger(CatalogHandler::class.java)

    /**
     * One release as it was released.
     *
     * Every detail here is read from what that release froze, never from the live catalog: its
     * resources from the release's own entries, its name, keywords, presentation and license from
     * the manifest snapshot. A catalog's details are version-dependent, and the browse page has
     * been showing the working copy's as though they applied to every release.
     */
    fun releaseDetail(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalog = request.catalog(tenantId)
        val catalogKey = catalog.key
        val version = request.pathVariable("version")

        val release = GetCatalogReleaseDetail(catalog, version).query()
            ?: return listWithError(request, "Catalog '${catalogKey.value}' has no release $version.")

        // What may be done with it: the same decisions the catalog page used to make per row.
        val publication = GetCatalogPublicationState(catalog).query()
        val summary = ListCatalogReleaseSummaries(catalog).query().firstOrNull { it.version == version }
        val bar = draftBar(tenantId, CatalogSegment(catalogKey, null, catalog.namespace))
        return ServerResponse.ok().page("catalogs/release") {
            "pageTitle" to "${release.catalog.name} v$version - Published release - Epistola"
            "tenantId" to tenantId.key
            "activeNavSection" to "catalogs"
            "catalogId" to catalog.address
            "release" to release
            // The other releases in the tenant with its content: "The same release as" (ADR 0034).
            "sameAs" to ListReleasesWithSameContent(catalog, version).query()?.releases.orEmpty()
            "releaseView" to summary?.let { releaseViews(listOf(it), publication).single() }
            "draftBar" to bar
            "liveIn" to bar.liveIn[version].orEmpty()
            "keywords" to release.catalog.keywords.sorted()
            // Alpha: only when a registry is configured and the reader may publish.
            "registryPublishAvailable" to CatalogRegistryHandler.canPublish(tenantId.key, app.epistola.suite.catalog.registry.ListReleaseRegistries(tenantId.key).query())
        }
    }

    fun releaseDialog(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val segment = request.catalogSegment()
        return fragmentOrPage(
            request,
            "catalogs/list",
            "release-dialog",
            releaseDialogModel(
                tenantId,
                segment,
                GetCatalogPublicationState(segment.catalogIn(tenantId.key)).query(),
            ),
        )
    }

    /**
     * What the release dialog renders from.
     *
     * The release pointer and the working-copy changes are read separately because only the second
     * builds the catalog: [CatalogReleaseReadiness] reads [GetCatalogResourceChanges], which already
     * answers whether anything is unreleased, so asking [GetCatalogReleaseStatus] as well would
     * build it twice for one dialog. The readiness is what the dialog gates Release on, the same
     * names the catalog page gates on.
     */
    private fun releaseDialogModel(
        tenantId: TenantId,
        segment: CatalogSegment,
        publication: CatalogPublicationState?,
        error: String? = null,
        version: String? = null,
        acknowledged: Set<String> = emptySet(),
        liveChosen: Set<String>? = null,
        replaceChosen: Boolean = false,
        afterwards: String = "stay",
        keepAs: String? = null,
    ): Map<String, Any?> {
        val readiness = CatalogReleaseReadiness.of(draftBar(tenantId, segment))
        val status = GetLatestCatalogRelease(segment.catalogIn(tenantId.key)).query()
        val live = PublishLiveChoice(
            environments = deploymentRows(segment.catalogIn(tenantId)).map { PublishLiveChoice.Target(it.environmentKey, it.environmentName, it.served.map { s -> s.version }, it.production) },
            mayDeploy = AuthContext.from(SecurityContext.current(), tenantId.key).has(Permission.ENVIRONMENT_DEPLOY),
            chosen = liveChosen,
            replace = replaceChosen,
        )
        return mapOf(
            // Where publishing makes the new version live, ticked in the dialog.
            "live" to live,
            "tenantId" to tenantId.key,
            // The catalog segment: the dialog releases the draft the page works in.
            "catalogId" to segment.value,
            // Its full name, which publishing to Exchange publishes it under (contract ADR 0003).
            "catalogRegistry" to GetCatalog(segment.catalogIn(tenantId.key)).query()?.registry,
            "catalogKey" to segment.key.value,
            // A LinkedHashSet: SpEL cannot call contains() on Kotlin's EmptySet.
            "acknowledgedBehind" to LinkedHashSet(acknowledged),
            "status" to status,
            "changes" to readiness.changes,
            "readiness" to readiness,
            // The number picked for the person from the kind of change; "advanced" may override it.
            "releaseVersion" to (version ?: readiness.publishVersion ?: status.suggestedNext.patch),
            "publication" to publication,
            "error" to error,
            // Publishing parallel work: where everyday work continues (`stay`, `keep`, `discard`).
            "afterwards" to afterwards,
            "keepAs" to (keepAs ?: readiness.drafts.chosen?.let { "${CatalogDraft.NEXT_LABEL} before ${it.label}".take(100) }),
        )
    }

    fun release(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val segment = request.catalogSegment()
        val catalogKey = segment.key
        val catalog = segment.catalogIn(request.tenantId())

        val form = request.form {
            field("version") {
                required()
                pattern("^\\d+\\.\\d+\\.\\d+$")
            }
        }

        // Resolved once: the dialog renders from it, and the checkbox is only an override where
        // the catalog policy allows one. Listing publication history on every error re-render
        // would be pure waste.
        val publication = GetCatalogPublicationState(catalog).query()

        // The releases this draft is behind that the author confirmed it may be (contract
        // `acknowledgedBehind`): one checkbox per release in the dialog.
        val acknowledged = request.params()["acknowledgedBehind"].orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        // The environments to make the new version live in, ticked in the dialog.
        val liveIn = request.params()["liveIn"].orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.toCollection(LinkedHashSet())
        // Deployed beside what each environment uses, unless the publisher chose to stop using those.
        val replaceLive = request.param("replaceLive").orElse("") == "true"
        // Publishing parallel work: whether everyday work continues from it, and what becomes of the next version.
        val afterwards = request.param("afterwards").orElse("stay").takeIf { it in setOf("stay", "keep", "discard") } ?: "stay"
        val keepAs = request.param("keepAs").orElse("").trim().ifEmpty { null }

        fun reRenderWithError(message: String): ServerResponse = refusedDialog(
            request,
            "release-dialog",
            releaseDialogModel(
                tenantId,
                segment,
                publication,
                error = message,
                version = form.formData["version"]?.ifBlank { null },
                acknowledged = acknowledged,
                liveChosen = liveIn,
                replaceChosen = replaceLive,
                afterwards = afterwards,
                keepAs = keepAs,
            ),
        )

        if (form.hasErrors()) {
            return reRenderWithError("The version number must be SemVer — MAJOR.MINOR.PATCH (e.g. 1.4.0).")
        }
        if (afterwards == "keep" && keepAs == null) return reRenderWithError("Give the current next release a name to keep it under.")

        val notes = form.formData["notes"]?.ifBlank { null }
        val publicationChoice = when {
            publication?.available != true || !publication.allowsReleaseOverride -> ReleasePublication.DEFAULT
            request.params().getFirst("publishToExchange") == "true" -> ReleasePublication.PUBLISH
            else -> ReleasePublication.SKIP
        }

        return try {
            if (publicationChoice == ReleasePublication.PUBLISH) {
                chooseNamespaceIfOffered(request, catalog)
            }
            // Publishing marks the changes ready itself (docs/catalog-workspace-navigation.md): to the
            // person, publishing is the decision that they are ready. Only for someone who may mark.
            val readiness = CatalogReleaseReadiness.of(draftBar(tenantId, segment))
            if (readiness.marksReadyOnPublish) {
                MarkCatalogReady(catalog, catalogVersion = segment.ref(tenantId.key)).execute()
                // So are the drafts of other catalogs it builds on, which publishing publishes first.
                readiness.publishedAlong.forEach { other ->
                    val otherCatalog = other.catalogIn(tenantId.key)
                    MarkCatalogReady(otherCatalog, catalogVersion = CatalogReleaseRef(otherCatalog, DraftRef.Id(requireNotNull(other.draftId)))).execute()
                }
            }
            val result = ReleaseCatalogDraft(
                catalog = catalog,
                version = form["version"],
                notes = notes,
                publication = publicationChoice,
                acknowledgedBehind = acknowledged,
                catalogVersion = segment.ref(tenantId.key),
                // The drafts of other catalogs it builds on, which the dialog lists, are released with it.
                releaseDependencies = true,
                // A version is new content: new notes alone are not one (ADR 0034), here as over REST and MCP.
                requireChange = true,
                continueEverydayWork = when (afterwards) {
                    "keep" -> ContinueEverydayWork(keepPreviousAs = keepAs)
                    "discard" -> ContinueEverydayWork()
                    else -> null
                },
            ).execute()

            // Then live where it was ticked. Published already, so a refusal says that it is, and
            // where it is not live yet, instead of reading as a failed publish.
            if (liveIn.isNotEmpty()) requirePermission(tenantId.key, Permission.ENVIRONMENT_DEPLOY)
            liveIn.forEach { environment ->
                try {
                    DeployCatalogRelease(EnvironmentId(EnvironmentKey.of(environment), tenantId), catalog, result.version, replace = replaceLive).execute()
                } catch (e: ValidationException) {
                    return reRenderWithError("Published v${result.version}, but it could not be deployed to $environment: ${e.message} Deploy it under Deployments.")
                } catch (e: RuntimeException) {
                    logger.warn("Making v{} of '{}' live in {} failed: {}", result.version, catalogKey.value, environment, e.message)
                    return reRenderWithError("Published v${result.version}, but it could not be deployed to $environment: ${e.message ?: "it was refused."} Deploy it under Deployments.")
                }
            }

            // Releasing is the start of something to watch, not the end of a dialog: publication
            // runs in the background, so land on the catalog where its progress is shown, in the next
            // version, which continues on the release.
            val landing = segment.at(null)
            request.htmx {
                dialogLocation(CatalogPages.browse(tenantId, landing))
                onFullPage {
                    redirect(CatalogPages.browse(tenantId, landing))
                }
            }
        } catch (e: CatalogReleaseVersionException) {
            reRenderWithError(e.message ?: "Invalid version number.")
        } catch (e: CatalogDraftUnchangedException) {
            reRenderWithError("This draft has nothing to release: nothing changed since v${e.base}. Change something first; new notes alone are no new version.")
        } catch (e: CatalogReleaseBehindException) {
            reRenderWithError(
                "Confirm that this draft may be released without " +
                    e.behind.joinToString { "v$it" } + ", which it is behind.",
            )
        } catch (e: Exception) {
            logger.warn("Failed to release catalog: ${e.message}", e)
            reRenderWithError(e.message ?: "Failed to release catalog.")
        }
    }

    /** Withdraws a queued publication; rejections stay on the Settings page, where Exchange is, like the publish action. */
    fun cancelPublication(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalog = request.catalog(tenantId)
        val catalogKey = catalog.key
        return try {
            CancelCatalogPublication(tenantId.key, UUID.fromString(request.pathVariable("publicationId"))).execute()
            redirect("/tenants/${tenantId.key}/catalogs/${catalog.address}/settings")
        } catch (failure: ValidationException) {
            logger.warn("Cancelling a publication of '{}' was rejected: {}", catalogKey.value, failure.message)
            section(request, "catalogs/settings", failure.message)
        }
    }

    /** Removes a release from the history, landing on Releases. Refusals -- a send in flight -- land there too, with the reason. */
    fun deleteRelease(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalog = request.catalog(tenantId)
        val catalogKey = catalog.key
        return try {
            DeleteCatalogRelease(catalog, request.pathVariable("version")).execute()
            redirect(CatalogPages.releases(catalog))
        } catch (failure: ReleaseInUseException) {
            section(request, "catalogs/releases", failure.message)
        } catch (failure: ValidationException) {
            logger.warn("Deleting a release of '{}' was rejected: {}", catalogKey.value, failure.message)
            section(request, "catalogs/releases", failure.message)
        }
    }

    /**
     * The button that offered this action was rendered from state that may have moved since — the
     * working copy edited in another tab, a second administrator queueing first. Those races are
     * exactly what the command validates, so a rejection returns the Settings page, where Exchange is,
     * with the reason rather than an error page.
     */
    fun publishCurrentRelease(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalog = request.catalog(tenantId)
        val catalogKey = catalog.key
        return try {
            chooseNamespaceIfOffered(request, catalog)
            // A version from the release list publishes that release; without one, the release the
            // catalog is currently on, which is what the catalog-level action sends.
            PublishCurrentCatalogRelease(
                catalog,
                version = request.param("version").orElse(null)?.ifBlank { null },
            ).execute()
            redirect("/tenants/${tenantId.key}/catalogs/${catalog.address}/settings")
        } catch (failure: ValidationException) {
            logger.warn("Publishing the current release of '{}' was rejected: {}", catalogKey.value, failure.message)
            section(request, "catalogs/settings", failure.message)
        }
    }
}

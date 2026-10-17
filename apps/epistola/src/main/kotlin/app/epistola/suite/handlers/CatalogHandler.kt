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
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.commands.AuthoredImportMode
import app.epistola.suite.catalog.commands.CatalogReleaseVersionException
import app.epistola.suite.catalog.commands.CatalogUpgradeConflictException
import app.epistola.suite.catalog.commands.CheckCatalogUpstream
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeleteCatalogRelease
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ForgetReleaseContent
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.ReleasePublication
import app.epistola.suite.catalog.commands.SetCatalogPublicationSettings
import app.epistola.suite.catalog.commands.UnregisterCatalog
import app.epistola.suite.catalog.commands.UpdateCatalogMetadata
import app.epistola.suite.catalog.commands.UpgradeCatalog
import app.epistola.suite.catalog.dependencies.DeclareUsedCatalogs
import app.epistola.suite.catalog.migrations.CatalogSchemaException
import app.epistola.suite.catalog.migrations.CatalogSchemaTooNewException
import app.epistola.suite.catalog.migrations.CatalogSchemaTooOldException
import app.epistola.suite.catalog.migrations.CatalogSchemaUnknownException
import app.epistola.suite.catalog.queries.BrowseCatalog
import app.epistola.suite.catalog.queries.CatalogReleaseSummary
import app.epistola.suite.catalog.queries.FindResourceUsages
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.GetCatalogRelease
import app.epistola.suite.catalog.queries.GetCatalogResourceChanges
import app.epistola.suite.catalog.queries.GetLatestCatalogRelease
import app.epistola.suite.catalog.queries.ListCatalogReleases
import app.epistola.suite.catalog.queries.ListRetainedReleases
import app.epistola.suite.catalog.queries.PreviewCatalogUpgrade
import app.epistola.suite.catalog.queries.PreviewInstall
import app.epistola.suite.catalog.queries.ResourceStatus
import app.epistola.suite.catalog.revisions.ReleaseInUseException
import app.epistola.suite.catalog.versions.CatalogVersionBehindException
import app.epistola.suite.catalog.versions.CatalogVersionReleasedException
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.EnvironmentNotFoundException
import app.epistola.suite.environments.ReleaseDependenciesMissingException
import app.epistola.suite.environments.ReleaseNotDeployableException
import app.epistola.suite.environments.commands.DeployRelease
import app.epistola.suite.environments.commands.UndeployRelease
import app.epistola.suite.environments.queries.ListDeployments
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.exchange.CancelCatalogPublication
import app.epistola.suite.exchange.CatalogPublicationState
import app.epistola.suite.exchange.ExchangeSourceUri
import app.epistola.suite.exchange.GetCatalogPublicationState
import app.epistola.suite.exchange.GetExchangeCatalogLink
import app.epistola.suite.exchange.PublishCurrentCatalogRelease
import app.epistola.suite.exchange.SetCatalogPublicationNamespace
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveFeatureToggles
import app.epistola.suite.htmx.CatalogSegment
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.catalogSegment
import app.epistola.suite.htmx.executeOrFormError
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.isHtmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.requirePermission
import app.epistola.suite.validation.FieldLimits
import app.epistola.suite.validation.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.util.UUID

private const val CATALOG_LOCALE_ATTRIBUTE = "system.locale"

@Component
class CatalogHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun list(request: ServerRequest): ServerResponse {
        val saved = request.param("saved").isPresent

        return ServerResponse.ok().page("catalogs/list") {
            "pageTitle" to "Catalogs - Epistola"
            "activeNavSection" to "catalogs"
            catalogListModel(request)
            if (saved) "saved" to true
        }
    }

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.CATALOG_MANAGE)
        return request.htmx {
            // In-app trigger (hx-get → #dialog-mount): just the dialog fragment.
            fragment("catalogs/new", "dialog") {
                "tenantId" to tenantId.key
            }
            // Direct navigation / boost: the host list page with the create
            // dialog embedded in its mount (openDialog=true), opened on load by JS.
            onNonHtmx {
                page("catalogs/list") {
                    catalogPageModel(request)
                    "openDialog" to true
                    "openDialogFragment" to "catalogs/new :: dialog"
                }
            }
        }
    }

    fun createCatalog(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.CATALOG_MANAGE)

        val form = request.form {
            field("slug") {
                required()
                pattern("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
                minLength(3)
                maxLength(50)
                asCatalogId()
            }
            field("name") {
                required()
            }
        }

        // Field validation (incl. slug/CatalogKey) and the command-level failure
        // (duplicate slug) both land as `errors` on the FormData, so they share one
        // error path — mirroring the environment/attribute conversions.
        val result = if (form.hasErrors()) {
            form
        } else {
            form.executeOrFormError {
                CreateCatalog(
                    tenantKey = tenantId.key,
                    id = CatalogKey.of(form["slug"]),
                    name = form["name"],
                ).execute()
            }
        }

        if (result.hasErrors()) {
            return request.htmx {
                // Re-render the form inside the dialog (retargeted to the form, not
                // the list) with inline errors + preserved values.
                dialogFieldErrors(
                    template = "catalogs/new",
                    fragmentName = "catalog-form",
                    formTarget = "#create-catalog-form",
                    formData = result,
                ) {
                    "tenantId" to tenantId.key
                }
                onNonHtmx {
                    page(422, "catalogs/list") {
                        catalogPageModel(request)
                        "openDialog" to true
                        "openDialogFragment" to "catalogs/new :: dialog"
                        "formData" to result.formData
                        "errors" to result.errors
                    }
                }
            }
        }

        // Success: close the dialog + OOB-refresh the list (the catalog-list
        // fragment toggles hx-swap-oob via the `oob` flag dialogSuccess injects).
        return request.htmx {
            dialogSuccess("catalogs/list", "catalog-list", "/tenants/${tenantId.key}/catalogs") {
                catalogListModel(request)
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/catalogs?saved=true") }
        }
    }

    fun registerForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.CATALOG_MANAGE)
        return request.htmx {
            // In-app trigger (hx-get → #dialog-mount): just the dialog fragment.
            fragment("catalogs/subscribe", "dialog") {
                "tenantId" to tenantId.key
            }
            // Direct navigation / boost: the host list page with the subscribe
            // dialog embedded in its mount (openDialog=true), opened on load by JS.
            onNonHtmx {
                page("catalogs/list") {
                    catalogPageModel(request)
                    "openDialog" to true
                    "openDialogFragment" to "catalogs/subscribe :: dialog"
                }
            }
        }
    }

    fun register(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.CATALOG_MANAGE)

        val form = request.form {
            field("sourceUrl") {
                required()
                maxLength(2000)
            }
        }

        if (form.hasErrors()) {
            return registerError(request, "Catalog URL is required.")
        }

        val sourceUrl = form.formData["sourceUrl"]!!
        val authTypeStr = form.formData["authType"] ?: "NONE"
        val authType = try {
            AuthType.valueOf(authTypeStr)
        } catch (_: IllegalArgumentException) {
            AuthType.NONE
        }
        val authCredential = form.formData["authCredential"]?.ifBlank { null }

        return try {
            RegisterCatalog(
                tenantKey = tenantId.key,
                sourceUrl = sourceUrl,
                authType = authType,
                authCredential = authCredential,
            ).execute()

            // Stay-on-list: close the dialog + OOB-refresh the list with the new catalog.
            request.htmx {
                dialogSuccess("catalogs/list", "catalog-list", "/tenants/${tenantId.key}/catalogs") {
                    catalogListModel(request)
                }
                onNonHtmx { redirect("/tenants/${tenantId.key}/catalogs?saved=true") }
            }
        } catch (e: ValidationException) {
            // The URL itself was refused (scheme, file roots, address): say why, it is the user's input.
            registerError(request, "Failed to register catalog: ${e.message}")
        } catch (e: Exception) {
            logger.warn("Failed to register catalog: ${e.message}", e)
            registerError(request, "Failed to register catalog. Check that the URL points to a valid catalog manifest.")
        }
    }

    /**
     * Register error → OOB-update just the dialog's form-error slot, leaving the
     * live form (URL, auth type, credential) exactly as the user left it.
     * dialogFieldErrors would re-render the body and reset the authType <select>
     * / credential toggle, so we keep the operational failure text-only here.
     */
    private fun registerError(request: ServerRequest, message: String): ServerResponse = request.htmx {
        dialogFormError("subscribe-catalog-error", message)
        // The subscribe form keeps a real no-JS fallback (method="post" th:action),
        // so this branch is reachable — re-embed the dialog rather than dropping the
        // user on a bare list with their URL/auth input gone. The dialog's
        // form-error slot renders the message from the standardized `error` key
        // (see epistola-web/form-error), so it shows INSIDE the reopened dialog
        // instead of only in the list banner behind the modal backdrop.
        onNonHtmx {
            page(422, "catalogs/list") {
                catalogPageModel(request)
                "openDialog" to true
                "openDialogFragment" to "catalogs/subscribe :: dialog"
                "error" to message
            }
        }
    }

    fun unregister(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = request.catalogId()

        return try {
            val force = request.param("force").orElse("false").toBoolean()
            UnregisterCatalog(tenantKey = tenantId.key, catalogKey = catalogKey, force = force).execute()

            return request.htmx {
                fragment("catalogs/list", "catalog-list") {
                    catalogListModel(request)
                }
                onNonHtmx {
                    ServerResponse.status(303)
                        .header("Location", "/tenants/${tenantId.key}/catalogs")
                        .build()
                }
            }
        } catch (e: app.epistola.suite.catalog.CatalogInUseException) {
            if (request.isHtmx) {
                ServerResponse.status(422)
                    .header("Content-Type", "application/json")
                    .body(mapOf("error" to (e.message ?: "Catalog is in use by other catalogs")))
            } else {
                listWithError(request, e.message ?: "Catalog is in use by other catalogs")
            }
        } catch (e: Exception) {
            logger.warn("Failed to unregister catalog: ${e.message}", e)
            if (request.isHtmx) {
                ServerResponse.status(422)
                    .header("Content-Type", "application/json")
                    .body(mapOf("error" to (e.message ?: "Failed to remove catalog.")))
            } else {
                listWithError(request, e.message ?: "Failed to remove catalog.")
            }
        }
    }

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
        val catalogKey = request.catalogId()
        val version = request.pathVariable("version")

        val release = GetCatalogRelease(tenantId.key, catalogKey, version).query()
            ?: return listWithError(request, "Catalog '${catalogKey.value}' has no release $version.")

        // What may be done with it: the same decisions the catalog page used to make per row.
        val publication = GetCatalogPublicationState(tenantId.key, catalogKey).query()
        val summary = ListCatalogReleases(tenantId.key, catalogKey).query().firstOrNull { it.version == version }
        val bar = draftBar(tenantId, CatalogSegment(catalogKey, null))
        return ServerResponse.ok().page("catalogs/release") {
            "pageTitle" to "${release.catalog.name} v$version - Published version - Epistola"
            "tenantId" to tenantId.key
            "activeNavSection" to "catalogs"
            "catalogId" to catalogKey.value
            "release" to release
            "releaseView" to summary?.let { releaseViews(listOf(it), publication).single() }
            "draftBar" to bar
            "liveIn" to bar.liveIn[version].orEmpty()
            "keywords" to release.catalog.keywords.sorted()
        }
    }

    fun releaseDialog(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val segment = request.catalogSegment()
        return ServerResponse.ok().render(
            "catalogs/list :: release-dialog",
            releaseDialogModel(
                tenantId,
                segment,
                GetCatalogPublicationState(tenantId.key, segment.key).query(),
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
    ): Map<String, Any?> {
        val readiness = CatalogReleaseReadiness.of(draftBar(tenantId, segment))
        val status = GetLatestCatalogRelease(tenantId.key, segment.key).query()
        val live = PublishLiveChoice(
            environments = deploymentRows(tenantId.key, segment.key).map { PublishLiveChoice.Target(it.environmentKey, it.environmentName, it.served.map { s -> s.version }) },
            mayDeploy = AuthContext.from(SecurityContext.current(), tenantId.key).has(Permission.ENVIRONMENT_DEPLOY),
            chosen = liveChosen,
        )
        return mapOf(
            // Where publishing makes the new version live, ticked in the dialog.
            "live" to live,
            "tenantId" to tenantId.key,
            // The catalog segment: the dialog releases the draft the page works in.
            "catalogId" to segment.value,
            // A LinkedHashSet: SpEL cannot call contains() on Kotlin's EmptySet.
            "acknowledgedBehind" to LinkedHashSet(acknowledged),
            "status" to status,
            "changes" to readiness.changes,
            "readiness" to readiness,
            // The number picked for the person from the kind of change; "advanced" may override it.
            "releaseVersion" to (version ?: readiness.publishVersion ?: status.suggestedNext.patch),
            "publication" to publication,
            "error" to error,
        )
    }

    fun release(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val segment = request.catalogSegment()
        val catalogKey = segment.key

        val form = request.form {
            field("version") {
                required()
                pattern("^\\d+\\.\\d+\\.\\d+$")
            }
        }

        // Resolved once: the dialog renders from it, and the checkbox is only an override where
        // the catalog policy allows one. Listing publication history on every error re-render
        // would be pure waste.
        val publication = GetCatalogPublicationState(tenantId.key, catalogKey).query()

        // The releases this draft is behind that the author confirmed it may be (contract
        // `acknowledgedBehind`): one checkbox per release in the dialog.
        val acknowledged = request.params()["acknowledgedBehind"].orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        // The environments to make the new version live in, ticked in the dialog.
        val liveIn = request.params()["liveIn"].orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.toCollection(LinkedHashSet())

        fun reRenderWithError(message: String): ServerResponse = ServerResponse.ok().render(
            "catalogs/list :: release-dialog",
            releaseDialogModel(tenantId, segment, publication, error = message, version = form.formData["version"]?.ifBlank { null }, acknowledged = acknowledged, liveChosen = liveIn),
        )

        if (form.hasErrors()) {
            return reRenderWithError("Version must be SemVer — MAJOR.MINOR.PATCH (e.g. 1.4.0).")
        }

        val notes = form.formData["notes"]?.ifBlank { null }
        val publicationChoice = when {
            publication?.available != true || !publication.allowsReleaseOverride -> ReleasePublication.DEFAULT
            request.params().getFirst("publishToExchange") == "true" -> ReleasePublication.PUBLISH
            else -> ReleasePublication.SKIP
        }

        return try {
            if (publicationChoice == ReleasePublication.PUBLISH) {
                chooseNamespaceIfOffered(request, tenantId.key, catalogKey)
            }
            // Publishing marks the changes ready itself (docs/catalog-workspace-navigation.md): to the
            // person, publishing is the decision that they are ready. Only for someone who may mark.
            val readiness = CatalogReleaseReadiness.of(draftBar(tenantId, segment))
            if (readiness.marksReadyOnPublish) {
                MarkCatalogReady(tenantId.key, catalogKey, catalogVersion = segment.ref(tenantId.key)).execute()
            }
            // And declares the catalogs it uses but does not declare, so the release can render them.
            if (readiness.declaresOnPublish) {
                DeclareUsedCatalogs(tenantId.key, catalogKey, catalogVersion = segment.ref(tenantId.key)).execute()
            }
            val result = ReleaseCatalogVersion(
                tenantKey = tenantId.key,
                catalogKey = catalogKey,
                version = form["version"],
                notes = notes,
                publication = publicationChoice,
                acknowledgedBehind = acknowledged,
                catalogVersion = segment.ref(tenantId.key),
            ).execute()

            // Then live where it was ticked. Published already, so a refusal says that it is, and
            // where it is not live yet, instead of reading as a failed publish.
            if (liveIn.isNotEmpty()) requirePermission(tenantId.key, Permission.ENVIRONMENT_DEPLOY)
            liveIn.forEach { environment ->
                try {
                    DeployRelease(EnvironmentId(EnvironmentKey.of(environment), tenantId), catalogKey, result.version, includeDependencies = true, replace = true).execute()
                } catch (e: ValidationException) {
                    return reRenderWithError("Published v${result.version}, but it could not be made live in $environment: ${e.message} Make it live under Live on the catalog page.")
                } catch (e: RuntimeException) {
                    logger.warn("Making v{} of '{}' live in {} failed: {}", result.version, catalogKey.value, environment, e.message)
                    return reRenderWithError("Published v${result.version}, but it could not be made live in $environment: ${e.message ?: "it was refused."} Make it live under Live on the catalog page.")
                }
            }

            // Releasing is the start of something to watch, not the end of a dialog: publication
            // runs in the background, so land on the catalog where its progress is shown — in the
            // draft the release carried on as, or at the current version when it was discarded.
            val landing = draftBar(tenantId, segment.at(null)).let { bar ->
                segment.at(result.openedDraft?.takeIf { it != bar.current?.version })
            }
            request.htmx {
                dialogLocation(CatalogPages.browse(tenantId, landing))
                onNonHtmx {
                    redirect(CatalogPages.browse(tenantId, landing))
                }
            }
        } catch (e: CatalogReleaseVersionException) {
            reRenderWithError(e.message ?: "Invalid release version.")
        } catch (e: CatalogVersionBehindException) {
            reRenderWithError(
                "Confirm that this draft may be released without " +
                    e.behind.joinToString { "v$it" } + ", which it is behind.",
            )
        } catch (e: Exception) {
            logger.warn("Failed to release catalog: ${e.message}", e)
            reRenderWithError(e.message ?: "Failed to release catalog.")
        }
    }

    /**
     * Applies a namespace picked on the publish form. Offered only when the catalog has no binding
     * and nothing would resolve one, so publishing would otherwise queue work that cannot move.
     */
    private fun chooseNamespaceIfOffered(request: ServerRequest, tenantKey: TenantKey, catalogKey: CatalogKey) {
        request.params().getFirst("chosenNamespace")?.trim()?.ifBlank { null }?.let { namespace ->
            SetCatalogPublicationNamespace(
                tenantKey = tenantKey,
                catalogKey = catalogKey,
                namespace = namespace,
                acknowledgeAlreadyPublished = request.params().getFirst("acknowledgeAlreadyPublished") == "true",
            ).execute()
        }
    }

    /** Rejects an unknown policy as a form error instead of letting `valueOf` become a 500. */
    private fun publicationPolicy(raw: String?): CatalogPublicationPolicy = CatalogPublicationPolicy.entries.firstOrNull { it.name == raw }
        ?: throw ValidationException("publicationPolicy", "Choose a valid publication policy.")

    /** Withdraws a queued publication; rejections stay on the catalog page like the publish action. */
    fun cancelPublication(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = request.catalogId()
        return try {
            CancelCatalogPublication(tenantId.key, UUID.fromString(request.pathVariable("publicationId"))).execute()
            ServerResponse.status(303)
                .header("Location", "/tenants/${tenantId.key}/catalogs/${catalogKey.value}/browse")
                .build()
        } catch (failure: ValidationException) {
            logger.warn("Cancelling a publication of '{}' was rejected: {}", catalogKey.value, failure.message)
            browse(request, error = failure.message)
        }
    }

    /**
     * Drops the content a release retained, and lands back on the catalog where the row now says
     * "not kept". A rejection stays on the page with its reason, like the publish actions beside it.
     */
    fun forgetReleaseContent(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = request.catalogId()
        return try {
            ForgetReleaseContent(tenantId.key, catalogKey, request.pathVariable("version")).execute()
            ServerResponse.status(303)
                .header("Location", CatalogPages.release(tenantId.key, catalogKey, request.pathVariable("version")))
                .build()
        } catch (failure: ReleaseInUseException) {
            browse(request, error = failure.message)
        } catch (failure: ValidationException) {
            logger.warn("Forgetting the content of a release of '{}' was rejected: {}", catalogKey.value, failure.message)
            browse(request, error = failure.message)
        }
    }

    /**
     * Deploys a release of this catalog to an environment, beside what it serves. With
     * `includeDependencies`, the releases of other catalogs it renders with are deployed along.
     * Refusals -- the release kept no content, the environment is gone, a dependency release is not
     * served -- come back on the catalog page with the reason.
     */
    fun deploy(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = request.catalogId()
        val environment = request.param("environmentId").orElse("").trim()
        val version = request.param("version").orElse("").trim()
        if (environment.isEmpty() || version.isEmpty()) return browse(request, error = "Choose an environment and a release to deploy.")
        val includeDependencies = request.param("includeDependencies").orElse("") == "true"
        return try {
            // One live version per catalog per environment in the UI: making one live replaces the others.
            DeployRelease(EnvironmentId(EnvironmentKey.of(environment), tenantId), catalogKey, version, includeDependencies, replace = true).execute()
            ServerResponse.status(303)
                .header("Location", "/tenants/${tenantId.key}/catalogs/${catalogKey.value}/browse")
                .build()
        } catch (failure: ReleaseNotDeployableException) {
            browse(request, error = failure.message)
        } catch (failure: EnvironmentNotFoundException) {
            browse(request, error = failure.message)
        } catch (failure: ReleaseDependenciesMissingException) {
            browse(
                request,
                error = null,
                deployRefusal = DeployRefusalView.of(
                    failure,
                    deployUrl = "/tenants/${tenantId.key.value}/catalogs/${catalogKey.value}/deployments",
                    fields = mapOf("environmentId" to environment, "version" to version),
                ),
            )
        }
    }

    /** Stops an environment serving one release of this catalog, given as the `version` field. */
    fun undeploy(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = request.catalogId()
        val version = request.param("version").orElse("").trim()
        if (version.isEmpty()) return browse(request, error = "Choose a release to undeploy.")
        UndeployRelease(EnvironmentId(EnvironmentKey.of(request.pathVariable("environmentId")), tenantId), catalogKey, version).execute()
        return ServerResponse.status(303)
            .header("Location", "/tenants/${tenantId.key}/catalogs/${catalogKey.value}/browse")
            .build()
    }

    /** One row per environment of the tenant, with every release of this catalog it serves, highest first. */
    private fun deploymentRows(tenantKey: TenantKey, catalogKey: CatalogKey): List<DeploymentRowView> {
        val served = ListDeployments(tenantKey, catalogKey).query().groupBy { it.environmentKey }
        return ListEnvironments(tenantId = app.epistola.suite.common.ids.TenantId(tenantKey)).query().map { environment ->
            DeploymentRowView(
                environmentKey = environment.id.value,
                environmentName = environment.name,
                served = served[environment.id].orEmpty().map { ServedReleaseView(it.version, it.deployedAt) },
            )
        }
    }

    /** Removes a release from the history. Refusals -- a send in flight -- stay on the page. */
    fun deleteRelease(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = request.catalogId()
        return try {
            DeleteCatalogRelease(tenantId.key, catalogKey, request.pathVariable("version")).execute()
            ServerResponse.status(303)
                .header("Location", "/tenants/${tenantId.key}/catalogs/${catalogKey.value}/browse")
                .build()
        } catch (failure: ReleaseInUseException) {
            browse(request, error = failure.message)
        } catch (failure: ValidationException) {
            logger.warn("Deleting a release of '{}' was rejected: {}", catalogKey.value, failure.message)
            browse(request, error = failure.message)
        }
    }

    /**
     * The button that offered this action was rendered from state that may have moved since — the
     * working copy edited in another tab, a second administrator queueing first. Those races are
     * exactly what the command validates, so a rejection returns the catalog page with the reason
     * rather than an error page.
     */
    fun publishCurrentRelease(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = request.catalogId()
        return try {
            chooseNamespaceIfOffered(request, tenantId.key, catalogKey)
            // A version from the release list publishes that release; without one, the release the
            // catalog is currently on, which is what the catalog-level action sends.
            PublishCurrentCatalogRelease(
                tenantId.key,
                catalogKey,
                version = request.param("version").orElse(null)?.ifBlank { null },
            ).execute()
            ServerResponse.status(303)
                .header("Location", "/tenants/${tenantId.key}/catalogs/${catalogKey.value}/browse")
                .build()
        } catch (failure: ValidationException) {
            logger.warn("Publishing the current release of '{}' was rejected: {}", catalogKey.value, failure.message)
            browse(request, error = failure.message)
        }
    }

    /**
     * The per-row "check for updates" button.
     *
     * Asks the source now and records the answer, so the next page load already knows it and the
     * background check does not immediately re-ask. Returns the same fragment the list renders from,
     * which is what keeps one definition of the Version cell.
     */
    fun upgradeCheck(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = request.catalogId()
        val catalog = GetCatalog(tenantId.key, catalogKey).query()
            ?: return ServerResponse.ok().render(
                "catalogs/list :: version-status",
                mapOf("tenantId" to tenantId.key, "catalogId" to catalogKey.value, "status" to null),
            )

        val check = try {
            CheckCatalogUpstream(tenantId.key, catalogKey).execute()
        } catch (e: Exception) {
            logger.warn("Upgrade check failed for catalog {}: {}", catalogKey, e.message)
            null
        }
        val status = CatalogVersionStatusView.of(
            catalog,
            check,
            CATALOG_SCHEMA_VERSION,
            fromExchange = ExchangeSourceUri.matches(catalog.sourceUrl),
        )
        return ServerResponse.ok().render(
            "catalogs/list :: version-status",
            mapOf(
                "tenantId" to tenantId.key,
                "catalogId" to catalogKey.value,
                "status" to status,
            ),
        )
    }

    private fun upgradeDialog(tenantId: app.epistola.suite.common.ids.TenantId, catalogKey: CatalogKey, error: String? = null): ServerResponse {
        val model = HashMap<String, Any?>()
        model["tenantId"] = tenantId.key
        model["catalogId"] = catalogKey.value
        model["error"] = error
        try {
            model["diff"] = PreviewCatalogUpgrade(tenantId.key, catalogKey).query()
        } catch (e: Exception) {
            logger.warn("Upgrade preview failed for catalog {}: {}", catalogKey, e.message)
            model["diff"] = null
            if (error == null) model["error"] = e.message ?: "Failed to preview upgrade. The remote catalog may be unavailable."
        }
        return ServerResponse.ok().render("catalogs/list :: upgrade-dialog", model)
    }

    fun upgradePreview(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = request.catalogId()
        return upgradeDialog(tenantId, catalogKey)
    }

    fun upgrade(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = request.catalogId()

        return try {
            val result = UpgradeCatalog(
                tenantKey = tenantId.key,
                catalogKey = catalogKey,
            ).execute()

            if (result.aborted) {
                val failed = result.installResults
                    .filter { it.status == InstallStatus.FAILED }
                    .joinToString(", ") { "${it.type}/${it.slug}" }
                return upgradeDialog(tenantId, catalogKey, "Upgrade aborted — these resources failed to install (nothing was changed, the version was not advanced): $failed")
            }

            request.htmx {
                fragment("catalogs/list", "upgrade-done") {}
                oob("catalogs/list", "catalog-list") {
                    catalogListModel(request)
                    "oob" to true
                }
                onNonHtmx {
                    redirect("/tenants/${tenantId.key}/catalogs?saved=true")
                }
            }
        } catch (e: CatalogUpgradeConflictException) {
            upgradeDialog(tenantId, catalogKey, e.message ?: "Upgrade blocked — resources are still in use.")
        } catch (e: Exception) {
            logger.warn("Failed to upgrade catalog: ${e.message}", e)
            upgradeDialog(tenantId, catalogKey, e.message ?: "Failed to upgrade catalog.")
        }
    }

    /**
     * `GET /{catalogId}/browse`: the catalog page — the next version, every version, where each is
     * live (docs/catalog-workspace-navigation.md). It is about the catalog, not one version, so a
     * segment naming a version goes to that version's own page.
     */
    fun browse(request: ServerRequest): ServerResponse {
        val segment = request.catalogSegment()
        if (segment.version != null) {
            return ServerResponse.status(303).header("Location", CatalogPages.draft(request.tenantId(), segment)).build()
        }
        return browse(request, error = null)
    }

    /**
     * `GET /{catalogId}/draft`: a draft's own page — what is not published yet, its contents and its
     * details. A plain segment is the next version; `key@version` another draft. A published version
     * has its own page, a subscribed catalog no drafts, and a catalog with nothing open its catalog
     * page, which offers to start; each goes there instead.
     */
    fun draftPage(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val segment = request.catalogSegment()
        val bar = try {
            draftBar(tenantId, segment)
        } catch (e: CatalogVersionReleasedException) {
            return ServerResponse.status(303).header("Location", CatalogPages.release(tenantId.key, segment.key, segment.version!!)).build()
        }
        if (bar.chosen == null) return ServerResponse.status(303).header("Location", CatalogPages.browse(tenantId, segment)).build()
        return catalogPage(request, "catalogs/version", segment, bar, error = null)
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
            catalogPage(request, "catalogs/version", segment, bar, error = null, draftError = message)
        } else {
            catalogPage(request, "catalogs/browse", segment.at(null), draftBar(tenantId, segment.at(null)), error = null, draftError = message)
        }
    }

    private fun draftBar(tenantId: TenantId, segment: CatalogSegment): CatalogDraftBar = CatalogDraftBar.of(tenantId.key, segment) { CatalogPages.draft(tenantId, it) }

    private fun browse(
        request: ServerRequest,
        error: String?,
        deployRefusal: DeployRefusalView? = null,
    ): ServerResponse {
        val tenantId = request.tenantId()
        val segment = request.catalogSegment().at(null)
        return catalogPage(request, "catalogs/browse", segment, draftBar(tenantId, segment), error, deployRefusal)
    }

    /** The catalog page or a draft's page ([template]): both show the catalog at [segment]. */
    private fun catalogPage(
        request: ServerRequest,
        template: String,
        segment: CatalogSegment,
        drafts: CatalogDraftBar,
        error: String?,
        deployRefusal: DeployRefusalView? = null,
        draftError: String? = null,
    ): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = segment.key
        val version = drafts.ref

        return try {
            val result = BrowseCatalog(tenantKey = tenantId.key, catalogKey = catalogKey, catalogVersion = version).query()
            val usages = FindResourceUsages(tenantKey = tenantId.key, catalogKey = catalogKey, catalogVersion = version).query()
            val usageCounts = usages.mapValues { it.value.size }
            val images = catalogImages(tenantId.key, catalogKey, version)
            val imagesBySlug = images.associateBy { it.id.value }
            val publication = if (result.catalog.type == CatalogType.AUTHORED) {
                GetCatalogPublicationState(tenantId.key, catalogKey).query()
            } else {
                null
            }
            // An authored catalog's releases are the ones it cut; a subscribed catalog's are the ones
            // it installed (each install records its release). Either can be deployed.
            val releases = ListCatalogReleases(tenantId.key, catalogKey).query()
            val deployments = deploymentRows(tenantId.key, catalogKey)
            // Only a release that kept its content can be deployed; one cut before releases retained
            // content has nothing to render from. Newest first, as the list is.
            val deployableVersions = releases.filter { it.retained }.map { it.version }
            val releaseViews = releaseViews(releases, publication)
            // The catalog's page on Exchange, when it came from there. Null for a plain URL
            // subscription, a ZIP import, or an authored catalog — the view falls back to the text
            // it rendered before. Resolved once per page: the rows append to it themselves.
            val exchangeCatalogUrl = GetExchangeCatalogLink(tenantId.key, result.catalog.sourceUrl).query()
            // Installing a subscribed catalog installs the whole manifest (#850), so the action
            // means something only while the manifest holds something this installation does not.
            // Gated on that rather than on the catalog being subscribed, which was always true and
            // left the button offering to install what was already installed.
            val hasUninstalledResources = result.resources.any { it.status == ResourceStatus.AVAILABLE }
            // Only an authored catalog has a next release to prepare.
            val authored = result.catalog.type == CatalogType.AUTHORED
            val readiness = if (authored) CatalogReleaseReadiness.of(drafts) else null
            val auth = AuthContext.from(SecurityContext.current(), tenantId.key)
            // From a new catalog to an application generating from it; until something is live.
            val gettingStarted = if (authored) {
                CatalogGettingStarted(
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
            ServerResponse.ok().page(template) {
                "pageTitle" to if (template == "catalogs/version") {
                    "${drafts.draftTitle} - ${result.catalog.name} - Epistola"
                } else {
                    "${result.catalog.name} - Catalog - Epistola"
                }
                "tenantId" to tenantId.key
                "activeNavSection" to "catalogs"
                "catalog" to result.catalog
                // The catalog segment every per-catalog link on the page carries (a draft, or plain).
                "catalogId" to segment.value
                "draftBar" to drafts
                "readiness" to readiness
                "gettingStarted" to gettingStarted
                // What the version holds, by kind, with a way to add each kind there.
                if (authored) {
                    "contents" to CatalogContents.of(
                        tenantId.key,
                        segment,
                        result.resources,
                        changeStates,
                    ) { auth.has(it) }
                }
                if (draftError != null) "nextReleaseError" to draftError
                "exchangeCatalogUrl" to exchangeCatalogUrl
                "publication" to publication
                "releases" to releaseViews
                "deployments" to deployments
                "deployableVersions" to deployableVersions
                "publicationError" to error
                "deployRefusal" to deployRefusal
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
    private fun releaseViews(releases: List<CatalogReleaseSummary>, publication: CatalogPublicationState?): List<CatalogReleaseView> {
        val publishedVersions = publication?.publications.orEmpty().mapTo(HashSet()) { it.version }
        return releases.map { release ->
            CatalogReleaseView(
                release = release,
                published = release.version in publishedVersions,
                // Nothing to forget about a release that kept nothing, which is also the state
                // forgetting one leaves behind -- so the action disappears once it has been used.
                forgettable = release.retained,
                publishable = release.retained &&
                    publication != null &&
                    publication.canPublish &&
                    publication.hasPublishableDestination &&
                    release.version !in publishedVersions,
            )
        }
    }

    fun metadataForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = request.catalogId()
        requirePermission(tenantId.key, Permission.CATALOG_MANAGE)
        val section = CatalogMetadataSection.from(request.param("section").orElse(CatalogMetadataSection.DETAILS.value))
        return ServerResponse.ok().render("catalogs/metadata :: dialog", catalogMetadataModel(tenantId.key, catalogKey, section))
    }

    fun updateMetadata(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = request.catalogId()
        requirePermission(tenantId.key, Permission.CATALOG_MANAGE)
        val section = CatalogMetadataSection.from(request.servletRequest().getParameter("section").orEmpty())
        val params = request.servletRequest().parameterMap

        return try {
            val catalog = catalogForMetadata(tenantId.key, catalogKey)
            val current = catalog.catalogMetadata

            if (section == CatalogMetadataSection.PUBLICATION) {
                SetCatalogPublicationSettings(
                    tenantKey = tenantId.key,
                    catalogKey = catalogKey,
                    policy = publicationPolicy(request.params().getFirst("publicationPolicy")),
                ).execute()
                // The namespace is a publishing decision, not catalog metadata, and carries its own
                // permission — so it travels as its own command and is simply absent for anyone who
                // may not set it.
                chooseNamespaceIfOffered(request, tenantId.key, catalogKey)
                return ServerResponse.noContent()
                    .header("HX-Redirect", "/tenants/${tenantId.key}/catalogs/${catalogKey.value}/browse")
                    .build()
            }

            UpdateCatalogMetadata(
                tenantKey = tenantId.key,
                catalogKey = catalogKey,
                name = if (section == CatalogMetadataSection.DETAILS) {
                    request.servletRequest().getParameter("name").orEmpty()
                } else {
                    catalog.name
                },
                description = if (section == CatalogMetadataSection.DETAILS) {
                    request.servletRequest().getParameter("description")?.trim()?.ifBlank { null }
                } else {
                    catalog.description
                },
                attributes = if (section == CatalogMetadataSection.ATTRIBUTES) {
                    parseLocaleMetadataAttribute(params, current.attributes)
                } else {
                    current.attributes
                },
                keywords = if (section == CatalogMetadataSection.KEYWORDS) parseMetadataKeywords(request) else current.keywords,
                presentation = if (section == CatalogMetadataSection.PRESENTATION) parseCatalogPresentation(request) else current.presentation,
                license = if (section == CatalogMetadataSection.LICENSE) parseCatalogLicense(request) else current.license,
            ).execute()

            // Details go out with a version, so they are edited on a draft's page: back to it.
            val back = if (catalog.type == CatalogType.AUTHORED) {
                CatalogPages.draft(tenantId, request.catalogSegment())
            } else {
                CatalogPages.browse(tenantId, request.catalogSegment())
            }
            ServerResponse.noContent()
                .header("HX-Redirect", back)
                .build()
        } catch (e: Exception) {
            logger.warn("Failed to update catalog metadata: {}", e.message)
            ServerResponse.status(422).render(
                "catalogs/metadata :: dialog",
                catalogMetadataModel(tenantId.key, catalogKey, section) +
                    ("error" to (e.message ?: "Failed to save catalog metadata.")),
            )
        }
    }

    private fun parseLocaleMetadataAttribute(
        params: Map<String, Array<String>>,
        current: List<AttributeAssignment>,
    ): List<AttributeAssignment> {
        val fieldName = "attribute_$CATALOG_LOCALE_ATTRIBUTE"
        require(params.keys.none { it.startsWith("attribute_") && it != fieldName }) {
            "Only the locale discovery attribute can be changed."
        }
        val preserved = current.filterNot { "${it.catalogKey}.${it.key}" == CATALOG_LOCALE_ATTRIBUTE }
        val locale = params[fieldName]?.firstOrNull()?.trim()?.ifBlank { null }
        return if (locale == null) preserved else preserved + AttributeAssignment("system", "locale", locale)
    }

    private fun parseMetadataKeywords(request: ServerRequest): Set<String> {
        val keywords = request.servletRequest().getParameter("keywords").orEmpty()
            .lines().map(String::trim).filter(String::isNotEmpty)
        require(keywords.distinct().size == keywords.size) { "Keywords must be unique (case is significant)." }
        return LinkedHashSet(keywords)
    }

    private fun parseCatalogPresentation(request: ServerRequest): CatalogPresentation? {
        val imageAssetSlugs = request.servletRequest().getParameterValues("imageAssetSlugs")
            ?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        val iconAssetSlug = request.servletRequest().getParameter("iconAssetSlug")?.trim()?.ifBlank { null }
        return if (iconAssetSlug != null || imageAssetSlugs.isNotEmpty()) {
            CatalogPresentation(iconAssetSlug, imageAssetSlugs)
        } else {
            null
        }
    }

    private fun parseCatalogLicense(request: ServerRequest): CatalogLicense? {
        val licenseName = request.servletRequest().getParameter("licenseName")?.trim().orEmpty()
        return if (licenseName.isNotEmpty() ||
            listOf("licenseSpdx", "licenseUrl", "licenseCopyright").any { !request.servletRequest().getParameter(it).isNullOrBlank() }
        ) {
            CatalogLicense(
                name = licenseName,
                spdxExpression = request.servletRequest().getParameter("licenseSpdx")?.trim()?.ifBlank { null },
                url = request.servletRequest().getParameter("licenseUrl")?.trim()?.ifBlank { null },
                copyrightText = request.servletRequest().getParameter("licenseCopyright")?.trim()?.ifBlank { null },
            )
        } else {
            null
        }
    }

    private fun catalogMetadataModel(
        tenantKey: app.epistola.suite.common.ids.TenantKey,
        catalogKey: CatalogKey,
        section: CatalogMetadataSection,
    ): Map<String, Any?> {
        val catalog = catalogForMetadata(tenantKey, catalogKey)
        val currentAttributes = catalog.catalogMetadata.attributes.associate { "${it.catalogKey}.${it.key}" to it.value }
        val descriptors = buildAttributeDescriptors(ListAttributeDefinitions(app.epistola.suite.common.ids.TenantId(tenantKey)).query())
            .filter { it.qualifiedKey == CATALOG_LOCALE_ATTRIBUTE }
        val images = catalogImages(tenantKey, catalogKey)
        val gallery = catalog.catalogMetadata.presentation?.imageKeys.orEmpty()

        return mapOf(
            "tenantId" to tenantKey,
            "catalog" to catalog,
            "section" to section.value,
            "sectionTitle" to section.title,
            "attributeFields" to descriptors.map { CatalogMetadataAttributeField(it, currentAttributes[it.qualifiedKey]) },
            "keywords" to catalog.catalogMetadata.keywords.inDisplayOrder(),
            "maxKeywords" to FieldLimits.MAX_KEYWORDS,
            "maxKeywordLength" to FieldLimits.MAX_KEYWORD_LENGTH,
            "images" to images,
            // The trailing "" is one empty picker to add the next image with. It is only drawn
            // when there are images to pick, because the section itself is guarded on that.
            "gallerySlugs" to (gallery + ""),
            "publication" to GetCatalogPublicationState(tenantKey, catalogKey).query(),
        )
    }

    /**
     * The images this catalog can present. The presentation controls are offered only when this is
     * non-empty (.agents/rules/ui-affordances.md), so every model that renders the metadata card
     * has to agree on it — hence one definition rather than the three it replaced.
     */
    private fun catalogImages(
        tenantKey: app.epistola.suite.common.ids.TenantKey,
        catalogKey: CatalogKey,
        version: app.epistola.suite.catalog.versions.CatalogVersionRef? = null,
    ) = ListAssets(tenantKey, catalogKey = catalogKey, catalogVersion = version).query()
        .filter { it.mediaType.category == AssetMediaCategory.IMAGE }

    private fun catalogForMetadata(
        tenantKey: app.epistola.suite.common.ids.TenantKey,
        catalogKey: CatalogKey,
    ) = GetCatalog(tenantKey, catalogKey).query()
        ?.also { require(it.type == CatalogType.AUTHORED) { "Subscribed catalog metadata is read-only." } }
        ?: throw IllegalArgumentException("Catalog not found: ${catalogKey.value}")

    private enum class CatalogMetadataSection(val value: String, val title: String) {
        DETAILS("details", "General details"),
        ATTRIBUTES("attributes", "Locale"),
        KEYWORDS("keywords", "Keywords"),
        PRESENTATION("presentation", "Presentation"),
        LICENSE("license", "License"),
        PUBLICATION("publication", "Exchange publication"),
        ;

        companion object {
            fun from(value: String): CatalogMetadataSection = entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unknown catalog metadata section: $value")
        }
    }

    data class CatalogMetadataAttributeField(val descriptor: AttributeDescriptor, val value: String?)

    data class CatalogPresentationAssetView(val slug: String, val asset: Asset?) {
        val available: Boolean get() = asset != null
    }

    fun resourceUsages(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = request.catalogId()
        val resourceType = request.param("type").orElse("")
        val resourceSlug = request.param("slug").orElse("")
        val key = "$resourceType:$resourceSlug"

        val usages = FindResourceUsages(tenantKey = tenantId.key, catalogKey = catalogKey).query()
        val resourceUsages = usages[key] ?: emptyList()

        return ServerResponse.ok().render(
            "catalogs/browse :: usage-detail",
            mapOf(
                "resourceType" to resourceType,
                "resourceSlug" to resourceSlug,
                "usages" to resourceUsages,
            ),
        )
    }

    fun installPreview(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = request.catalogId()

        return try {
            val preview = PreviewInstall(
                tenantKey = tenantId.key,
                catalogKey = catalogKey,
            ).query()

            ServerResponse.ok().render(
                "catalogs/browse :: install-preview",
                mapOf(
                    "tenantId" to tenantId.key,
                    "catalog" to mapOf("id" to catalogKey.value),
                    "allResources" to preview.resources,
                ),
            )
        } catch (e: Exception) {
            logger.warn("Failed to preview install: ${e.message}", e)
            ServerResponse.ok().render(
                "catalogs/browse :: install-preview-error",
                mapOf("error" to (e.message ?: "Failed to resolve dependencies.")),
            )
        }
    }

    fun install(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = request.catalogId()

        return try {
            val results = InstallFromCatalog(
                tenantKey = tenantId.key,
                catalogKey = catalogKey,
            ).execute()

            val failed = results.filter { it.status == InstallStatus.FAILED }
            val message = if (failed.isNotEmpty()) {
                null to "Failed to install: ${failed.joinToString(", ") { it.slug }}"
            } else {
                val installed = results.count { it.status == InstallStatus.INSTALLED }
                val updated = results.count { it.status == InstallStatus.UPDATED }
                val parts = listOfNotNull(
                    if (installed > 0) "$installed installed" else null,
                    if (updated > 0) "$updated updated" else null,
                )
                "Resources ${parts.joinToString(", ")}." to null
            }

            browseFragment(request, catalogKey, message.first, message.second)
        } catch (e: Exception) {
            logger.warn("Failed to install from catalog: ${e.message}", e)
            browseFragment(request, catalogKey, errorMessage = e.message ?: "Failed to install resources from catalog.")
        }
    }

    private fun browseFragment(
        request: ServerRequest,
        catalogKey: CatalogKey,
        successMessage: String? = null,
        errorMessage: String? = null,
    ): ServerResponse {
        val tenantId = request.tenantId()
        val result = BrowseCatalog(tenantKey = tenantId.key, catalogKey = catalogKey).query()
        val images = catalogImages(tenantId.key, catalogKey)
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
            onNonHtmx {
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
     * The Import-ZIP dialog, mirroring [newForm]/[registerForm]: an in-app
     * trigger (hx-get → #dialog-mount) gets just the `dialog` fragment; direct
     * navigation / boost gets the host list page with the dialog embedded in its
     * mount (openDialog=true), opened on load by JS. Gated on TEMPLATE_EDIT —
     * the same permission [ImportCatalogZip] enforces.
     */
    fun importForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TEMPLATE_EDIT)
        return request.htmx {
            fragment("catalogs/import", "dialog") { "tenantId" to tenantId.key }
            onNonHtmx {
                page("catalogs/list") {
                    catalogPageModel(request)
                    "openDialog" to true
                    "openDialogFragment" to "catalogs/import :: dialog"
                }
            }
        }
    }

    fun importZip(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TEMPLATE_EDIT)

        val multipartData = request.multipartData()
        val filePart = multipartData["file"]?.firstOrNull()
            ?: return importError(request, "No file provided")

        val zipBytes = filePart.inputStream.use { it.readAllBytes() }

        val catalogTypeStr = multipartData["catalogType"]?.firstOrNull()?.let {
            String(it.inputStream.readAllBytes()).trim()
        } ?: "AUTHORED"
        val catalogType = try {
            CatalogType.valueOf(catalogTypeStr)
        } catch (_: Exception) {
            return importError(request, "Invalid catalog type: $catalogTypeStr")
        }

        // Only consulted when the catalog already exists as AUTHORED; ignored
        // for a new catalog or SUBSCRIBED. MERGE = keep local-only resources;
        // REPLACE = mirror the ZIP (delete local-only, conflict-checked).
        val authoredMode = multipartData["authoredMode"]?.firstOrNull()?.let {
            String(it.inputStream.readAllBytes()).trim()
        }?.let { runCatching { AuthoredImportMode.valueOf(it) }.getOrNull() }
            ?: AuthoredImportMode.MERGE

        // Set when the operator confirmed updating an AUTHORED ZIP that is below the
        // current catalog schema version but migratable (re-submitted from the
        // "update?" prompt).
        val confirmMigration = multipartData["confirmMigration"]?.firstOrNull()?.let {
            String(it.inputStream.readAllBytes()).trim().equals("true", ignoreCase = true)
        } ?: false

        return try {
            val result = ImportCatalogZip(
                tenantKey = tenantId.key,
                zipBytes = zipBytes,
                catalogType = catalogType,
                authoredMode = authoredMode,
                confirmMigration = confirmMigration,
            ).execute()

            if (request.isHtmx) {
                ServerResponse.ok()
                    .header("HX-Redirect", "/tenants/${tenantId.key}/catalogs/${result.catalogKey}/browse")
                    .build()
            } else {
                ServerResponse.status(303)
                    .header("Location", "/tenants/${tenantId.key}/catalogs/${result.catalogKey}/browse")
                    .build()
            }
        } catch (e: CatalogSchemaException) {
            // The uploaded catalog's wire format is too new, too old, or
            // unrecognised — the migrator rejected it before binding. Render the
            // actionable remediation message inline, so the operator knows whether to upgrade
            // this instance or re-export from a current source.
            logger.warn("Import blocked by catalog wire-format version: ${e.message}")
            val title = when (e) {
                is CatalogSchemaTooNewException -> "Import blocked: catalog format is too new"
                is CatalogSchemaTooOldException -> "Import blocked: catalog format is too old"
                is CatalogSchemaUnknownException -> "Import blocked: unrecognised catalog format"
            }
            ServerResponse.ok().render(
                "catalogs/import :: import-schema-error",
                mapOf(
                    "schemaErrorTitle" to title,
                    "schemaErrorDetail" to (e.message ?: "Incompatible catalog wire format."),
                ),
            )
        } catch (e: CatalogMigrationConfirmationRequiredException) {
            // AUTHORED ZIP below the current catalog schema version but migratable:
            // updating mutates the imported content, so prompt before importing. The
            // "Update" button re-submits the same form with confirmMigration=true.
            ServerResponse.ok().render(
                "catalogs/import :: import-migration-confirm",
                mapOf(
                    "tenantId" to tenantId.key,
                    "fromSchemaVersion" to e.fromVersion,
                    "toSchemaVersion" to e.toVersion,
                    "migrationNotices" to e.notices,
                ),
            )
        } catch (e: Exception) {
            logger.warn("Failed to import catalog from ZIP: ${e.message}", e)
            importError(request, e.message ?: "Failed to import catalog")
        }
    }

    /**
     * Plain single-message import failures go through the global form-error
     * slot (shaped 422; see HtmxDsl.globalFormError). The structured import
     * responses (schema error, migration confirm) keep
     * rendering into the form's dedicated #import-error target instead — the
     * text-only slot cannot hold them. Because the shaped response's primary
     * swap is `none`, stale structured content in #import-error is OOB-reset
     * in the same response.
     */
    private fun importError(request: ServerRequest, error: String): ServerResponse = request.htmx {
        globalFormError("import-catalog-error", error)
        oob("catalogs/import", "import-error-reset")
        // Defensive only: unlike the subscribe/create forms, the import form is
        // HTMX-only (no method/action fallback — a file upload has nothing to
        // restore on a re-render anyway), so a genuine non-HTMX POST cannot reach
        // this. Kept consistent with registerError so the branch stays correct if a
        // fallback is ever added; `error` renders in the dialog's form-error slot.
        onNonHtmx {
            page(422, "catalogs/list") {
                catalogPageModel(request)
                "openDialog" to true
                "openDialogFragment" to "catalogs/import :: dialog"
                "error" to error
            }
        }
    }

    /** The export dialog: the working copy, or a release that kept its content, as it was released. */
    fun exportCheck(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = request.catalogId()
        return ServerResponse.ok().render(
            "catalogs/list :: export-confirmation-dialog",
            mapOf(
                "tenantId" to tenantId.key,
                "catalogId" to catalogKey.value,
                // Only releases that retained their content can be handed over as released;
                // the dialog offers exactly these, so the choice cannot fail at download.
                "retainedReleases" to ListRetainedReleases(tenantId.key, catalogKey).query(),
            ),
        )
    }

    fun export(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogKey = request.catalogId()

        return try {
            val result = ExportCatalogZip(
                tenantKey = tenantId.key,
                catalogKey = catalogKey,
                version = request.param("version").orElse(null)?.ifBlank { null },
            ).execute()

            ServerResponse.ok()
                .header("Content-Type", "application/zip")
                .header("Content-Disposition", "attachment; filename=\"${result.filename}\"")
                .body(result.zipBytes)
        } catch (e: Exception) {
            logger.warn("Failed to export catalog: ${e.message}", e)
            ServerResponse.badRequest()
                .header("Content-Type", "application/json")
                .body(mapOf("error" to (e.message ?: "Failed to export catalog")))
        }
    }

    /** An environment, and the releases of the catalog on screen it serves, highest first (empty when none). */
    data class DeploymentRowView(
        val environmentKey: String,
        val environmentName: String,
        val served: List<ServedReleaseView>,
    ) {
        val servedVersions: Set<String> get() = served.mapTo(HashSet()) { it.version }
    }

    /** One release an environment serves, and since when. */
    data class ServedReleaseView(val version: String, val deployedAt: java.time.OffsetDateTime)

    /** One release as the catalog page shows it: the release, and what may be done with it. */
    data class CatalogReleaseView(
        val release: CatalogReleaseSummary,
        /** Already sent to Exchange, so the row says so instead of offering to send it again. */
        val published: Boolean,
        /** Publishing this release would be accepted — the command decides the same way. */
        val publishable: Boolean,
        /** There is retained content to drop, so forgetting it would actually reclaim something. */
        val forgettable: Boolean,
    )

    /**
     * The catalog list as every full-page render of it needs it: the list, plus whether the
     * Organise action in its header is available. The create, register and import dialogs render
     * the list page behind them, and used to lose that action there.
     */
    private fun ModelBuilder.catalogListModel(request: ServerRequest) {
        catalogListModel(request.tenantId().key)
        "resourceRelocationEnabled" to request.relocationEnabled()
    }

    /**
     * The full-page list model used by the newForm / createCatalog non-HTMX
     * branches so the list renders behind the embedded create dialog. Adds the
     * page chrome the host list page needs on top of [catalogListModel].
     */
    private fun ModelBuilder.catalogPageModel(request: ServerRequest) {
        "pageTitle" to "Catalogs - Epistola"
        "activeNavSection" to "catalogs"
        catalogListModel(request)
    }

    private fun listWithError(request: ServerRequest, error: String): ServerResponse = ServerResponse.ok().page("catalogs/list") {
        "pageTitle" to "Catalogs - Epistola"
        "activeNavSection" to "catalogs"
        catalogListModel(request)
        "error" to error
    }

    /**
     * Whether to offer the Organise entry point. Alpha, so absent unless the tenant enables it.
     */
    private fun ServerRequest.relocationEnabled() = ResolveFeatureToggles(tenantId().key).query()[KnownFeatures.RESOURCE_RELOCATION] == true
}

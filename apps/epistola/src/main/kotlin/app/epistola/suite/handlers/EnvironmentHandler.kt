// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.environments.ClearEnvironmentOverride
import app.epistola.suite.environments.DeploymentLogEntry
import app.epistola.suite.environments.EnvironmentOverrideRefusedException
import app.epistola.suite.environments.ListEnvironmentOverrides
import app.epistola.suite.environments.SetEnvironmentOverride
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeleteEnvironment
import app.epistola.suite.environments.commands.DeployRelease
import app.epistola.suite.environments.commands.UndeployRelease
import app.epistola.suite.environments.commands.UpdateEnvironment
import app.epistola.suite.environments.queries.GetEnvironment
import app.epistola.suite.environments.queries.GetEnvironmentLatest
import app.epistola.suite.environments.queries.ListDeployableReleases
import app.epistola.suite.environments.queries.ListDeploymentHistory
import app.epistola.suite.environments.queries.ListDeployments
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.environments.queries.ListImplicitDeployments
import app.epistola.suite.generation.release.CatalogSelection
import app.epistola.suite.handlers.CatalogPages
import app.epistola.suite.handlers.DeployRefusalView
import app.epistola.suite.handlers.DeploymentUsageText
import app.epistola.suite.htmx.CatalogSegment
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.environmentId
import app.epistola.suite.htmx.executeOrFormError
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.queryParam
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.requirePermission
import app.epistola.suite.tenants.queries.GetTenant
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.time.OffsetDateTime

@Component
class EnvironmentHandler {
    fun list(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val tenant = GetTenant(tenantId.key).query() ?: return ServerResponse.notFound().build()
        val environments = ListEnvironments(tenantId = tenantId).query()
        return ServerResponse.ok().page("environments/list") {
            "pageTitle" to "Environments - Epistola"
            "tenant" to tenant
            "tenantId" to tenantId.key
            "environments" to environments
        }
    }

    fun search(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val searchTerm = request.queryParam("q")
        val environments = ListEnvironments(tenantId = tenantId, searchTerm = searchTerm).query()
        return request.htmx {
            fragment("environments/list", "rows") {
                "tenantId" to tenantId.key
                "environments" to environments
                // Lets the fragment tell "no matches" apart from "none yet". A search swaps only
                // this fragment, so the distinction has to reach it.
                "searchTerm" to searchTerm.orEmpty()
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/environments") }
        }
    }

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TENANT_SETTINGS)
        return request.htmx {
            // In-app trigger (hx-get → #dialog-mount): just the dialog fragment.
            fragment("environments/new", "dialog") {
                "tenantId" to tenantId.key
            }
            // Direct navigation / boost: the host list page with the dialog
            // embedded in its mount (openDialog=true), opened on load by the JS.
            onNonHtmx {
                page("environments/list") {
                    listModel(tenantId)
                    "openDialog" to true
                }
            }
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TENANT_SETTINGS)

        val form = request.form {
            field("slug") {
                required()
                asEnvironmentId()
            }
            field("name") {
                required()
                maxLength(100)
            }
            field("production") {}
        }

        // Field validation and the command-level failure (duplicate slug) both
        // land as `errors` on the FormData, so they share one error path.
        val result = if (form.hasErrors()) {
            form
        } else {
            form.executeOrFormError {
                CreateEnvironment(
                    id = EnvironmentId(form.getEnvironmentId("slug")!!, tenantId),
                    name = form["name"],
                    production = form["production"] == "true",
                ).execute()
            }
        }

        if (result.hasErrors()) {
            return request.htmx {
                // Re-render the form inside the dialog (retargeted to the form,
                // not the list) with inline errors + preserved values. `tenantId`
                // is prefill the form needs for its th:hx-post URL.
                dialogFieldErrors(
                    template = "environments/new",
                    fragmentName = "environment-form",
                    formTarget = "#create-environment-form",
                    formData = result,
                ) {
                    "tenantId" to tenantId.key
                }
                onNonHtmx {
                    page(422, "environments/list") {
                        listModel(tenantId)
                        "openDialog" to true
                        "formData" to result.formData
                        "errors" to result.errors
                    }
                }
            }
        }

        val environments = ListEnvironments(tenantId = tenantId).query()
        return request.htmx {
            // Success: close the dialog + refresh the list out-of-band. Global
            // attributes the list fragment needs (`auth` for permission checks)
            // are injected by HtmxFragmentModelContributor on the OOB render path.
            dialogSuccess("environments/list", "environment-list", "/tenants/${tenantId.key}/environments") {
                "tenantId" to tenantId.key
                "environments" to environments
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/environments") }
        }
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val environmentId = request.environmentId(tenantId)
            ?: return ServerResponse.badRequest().build()

        try {
            DeleteEnvironment(id = environmentId).execute()
        } catch (e: EnvironmentInUseException) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to e.message))
        }

        // Refresh the whole list region so the last delete flips to the empty
        // state (the empty-state lives in `environment-list`, outside the rows
        // tbody). Direct targeted swap into #environment-list (outerHTML), not
        // an OOB swap, so `oob` stays unset and hx-swap-oob renders null.
        val environments = ListEnvironments(tenantId = tenantId).query()
        return request.htmx {
            fragment("environments/list", "environment-list") {
                "tenantId" to tenantId.key
                "environments" to environments
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/environments") }
        }
    }

    /**
     * An environment: every release of each catalog it serves, a way to deploy another, and what it
     * served over time, with each earlier release offered again where it can still be deployed.
     */
    fun detail(request: ServerRequest): ServerResponse = detail(request, error = null)

    private fun detail(request: ServerRequest, error: String?, refusal: DeployRefusalView? = null): ServerResponse {
        val tenantId = request.tenantId()
        val environmentId = request.environmentId(tenantId) ?: return ServerResponse.notFound().build()
        val environment = GetEnvironment(environmentId).query() ?: return ServerResponse.notFound().build()

        val deployable = ListDeployableReleases(tenantId.key).query()
        val catalogNames = deployable.associate { it.catalogKey to it.catalogName }
        val served = ListDeployments(tenantId.key, environmentKey = environmentId.key).query()
        val servedReleases = served.map { it.catalogKey to it.version }.toSet()
        val deployableSet = deployable.map { it.catalogKey to it.version }.toSet()

        val history = ListDeploymentHistory(environmentId).query().map { entry ->
            DeploymentHistoryView(
                catalogKey = entry.catalogKey.value,
                catalogName = catalogNames[entry.catalogKey] ?: entry.catalogKey.value,
                deployed = entry.action == DeploymentAction.DEPLOYED,
                change = historyText(entry, catalogNames[entry.catalogKey] ?: entry.catalogKey.value),
                version = entry.version,
                previousVersion = entry.previousVersion,
                changedAt = entry.changedAt,
                changedByName = entry.changedByName,
                // Offered again only when deploying it would change something and can succeed:
                // the release still kept its content, and the environment does not serve it now.
                // An override names no release to deploy.
                redeployable = entry.version != null &&
                    entry.action in setOf(DeploymentAction.DEPLOYED, DeploymentAction.UNDEPLOYED) &&
                    (entry.catalogKey to entry.version) in deployableSet &&
                    (entry.catalogKey to entry.version) !in servedReleases,
            )
        }

        val allNames = ListCatalogs(tenantId.key).query().associate { it.id to it.name }
        val implicit = ListImplicitDeployments(tenantId.key, environmentId.key).query().map { used ->
            ImplicitReleaseView(
                catalogKey = used.catalogKey.value,
                catalogName = allNames[used.catalogKey] ?: used.catalogKey.value,
                version = used.version,
                neededBy = used.neededBy.map { allNames[it] ?: it.value },
            )
        }

        // What a request naming no version gets here, per catalog: the override, or the highest it serves.
        val latest = GetEnvironmentLatest(tenantId.key, environmentId.key).query()
        val overridden = ListEnvironmentOverrides(tenantId.key, environmentId.key).query().associateBy { it.catalogKey }

        return ServerResponse.ok().page("environments/detail") {
            "pageTitle" to "${environment.name} - Environments - Epistola"
            "tenantId" to tenantId.key
            "environment" to environment
            "error" to error
            "refusal" to refusal
            "deployments" to served.map { deployment ->
                ServedReleaseView(
                    catalogKey = deployment.catalogKey.value,
                    catalogName = catalogNames[deployment.catalogKey] ?: deployment.catalogKey.value,
                    version = deployment.version,
                    deployedAt = deployment.deployedAt,
                    documentsRequested = deployment.documentsRequested,
                    lastUsedAt = deployment.lastUsedAt,
                    latest = (latest[deployment.catalogKey] as? CatalogSelection.Release)?.version == deployment.version,
                    overridden = overridden[deployment.catalogKey]?.version == deployment.version,
                    catalogOverridden = overridden.containsKey(deployment.catalogKey),
                )
            }
            // The drafts it follows, live: documents here come from them.
            "following" to overridden.values.filter { it.draftId != null }.map { override ->
                FollowedDraftView(
                    catalogKey = override.catalogKey.value,
                    catalogName = allNames[override.catalogKey] ?: override.catalogKey.value,
                    draftLabel = override.label,
                    href = CatalogPages.draft(tenantId, CatalogSegment(override.catalogKey, override.draftId)),
                )
            }
            "releaseGroups" to deployable.groupBy { it.catalogKey }.map { (catalogKey, releases) ->
                ReleaseGroupView(
                    catalogKey = catalogKey.value,
                    catalogName = releases.first().catalogName,
                    versions = releases.map { it.version },
                    // A HashSet, not Kotlin's EmptySet, whose class the template's `contains` cannot reach.
                    servedVersions = releases.map { it.version }.filterTo(HashSet()) { (catalogKey to it) in servedReleases },
                )
            }
            "history" to history
            // Used here without being deployed: what the deployed versions are pinned to.
            "implicit" to implicit
        }
    }

    /**
     * Deploys the chosen release, given as `catalog@version`, beside what the environment serves.
     * With `includeDependencies`, the releases of other catalogs it renders with are deployed along.
     * Refusals stay on the page; one for missing dependencies lists them, and offers deploying them
     * along when that would succeed.
     */
    fun deploy(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val environmentId = request.environmentId(tenantId) ?: return ServerResponse.notFound().build()
        val release = request.param("release").orElse("").trim()
        val catalog = release.substringBefore('@', missingDelimiterValue = "")
        val version = release.substringAfter('@', missingDelimiterValue = "")
        val catalogKey = CatalogKey.validateOrNull(catalog)
        if (catalogKey == null || version.isEmpty()) return detail(request, error = "Choose a release to deploy.")
        val includeDependencies = request.param("includeDependencies").orElse("") == "true"
        // Beside what the environment serves, unless the deployer chose to stop using the others.
        val replace = request.param("replace").orElse("") == "true"
        return try {
            DeployRelease(environmentId, catalogKey, version, includeDependencies, replace = replace).execute()
            redirectTo(environmentId)
        } catch (failure: ReleaseNotDeployableException) {
            detail(request, error = failure.message)
        } catch (failure: ReleaseDependenciesMissingException) {
            detail(
                request,
                error = null,
                refusal = DeployRefusalView.of(
                    failure,
                    deployUrl = "/tenants/${tenantId.key.value}/environments/${environmentId.key.value}/deployments",
                    fields = mapOf("release" to release),
                ),
            )
        }
    }

    /**
     * `POST /{environmentId}/overrides` with `catalog` and `version`: the environment uses that
     * release, one it serves, as its latest of the catalog. `POST /{environmentId}/overrides/{catalogId}/clear`
     * ends the override: it uses the highest release it serves again.
     */
    fun setOverride(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.ENVIRONMENT_DEPLOY)
        val environmentId = request.environmentId(tenantId) ?: return ServerResponse.notFound().build()
        val catalogKey = CatalogKey.validateOrNull(request.param("catalog").orElse("")) ?: return detail(request, error = "Choose a catalog.")
        val version = request.param("version").orElse("").trim().ifEmpty { null } ?: return detail(request, error = "Choose a release.")
        return try {
            SetEnvironmentOverride(environmentId, catalogKey, version = version).execute()
            redirectTo(environmentId)
        } catch (e: EnvironmentOverrideRefusedException) {
            detail(request, error = e.message)
        }
    }

    fun clearOverride(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.ENVIRONMENT_DEPLOY)
        val environmentId = request.environmentId(tenantId) ?: return ServerResponse.notFound().build()
        val catalogKey = CatalogKey.validateOrNull(request.pathVariable("catalogId")) ?: return ServerResponse.notFound().build()
        ClearEnvironmentOverride(environmentId, catalogKey).execute()
        return redirectTo(environmentId)
    }

    /** `POST /{environmentId}/production` with `production` (`true` or `false`): marks it, or not. */
    fun setProduction(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TENANT_SETTINGS)
        val environmentId = request.environmentId(tenantId) ?: return ServerResponse.notFound().build()
        val environment = GetEnvironment(environmentId).query() ?: return ServerResponse.notFound().build()
        return try {
            UpdateEnvironment(environmentId, environment.name, production = request.param("production").orElse("") == "true").execute()
            redirectTo(environmentId)
        } catch (e: EnvironmentOverrideRefusedException) {
            detail(request, error = e.message)
        }
    }

    private fun historyText(entry: DeploymentLogEntry, catalogName: String): String = when (entry.action) {
        DeploymentAction.DEPLOYED -> "Deployed $catalogName v${entry.version}"

        DeploymentAction.UNDEPLOYED -> "Undeployed $catalogName v${entry.version}"

        DeploymentAction.OVERRIDE_SET -> entry.version?.let { "Uses $catalogName v$it as its latest" } ?: "Follows ${entry.draftLabel} of $catalogName"

        DeploymentAction.OVERRIDE_CLEARED ->
            "Uses the highest $catalogName release it serves again" +
                (entry.version?.let { ", not v$it" } ?: entry.draftLabel?.let { ", not $it" }.orEmpty())
    }

    /** Stops the environment serving one release of a catalog, given as the `version` field. */
    fun undeploy(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val environmentId = request.environmentId(tenantId) ?: return ServerResponse.notFound().build()
        val catalogKey = CatalogKey.validateOrNull(request.pathVariable("catalogId")) ?: return ServerResponse.notFound().build()
        val version = request.param("version").orElse("").trim()
        if (version.isEmpty()) return detail(request, error = "Choose a release to undeploy.")
        UndeployRelease(environmentId, catalogKey, version).execute()
        return redirectTo(environmentId)
    }

    private fun redirectTo(environmentId: EnvironmentId): ServerResponse = ServerResponse.status(303)
        .header("Location", "/tenants/${environmentId.tenantKey.value}/environments/${environmentId.key.value}")
        .build()

    /** One release of a catalog the environment serves. */
    data class ServedReleaseView(
        val catalogKey: String,
        val catalogName: String,
        val version: String,
        val deployedAt: OffsetDateTime,
        /** Generation requests through this environment accepted for it since it was deployed. */
        val documentsRequested: Long = 0,
        /** When the last was; null when none was, so it may be safe to undeploy. */
        val lastUsedAt: OffsetDateTime? = null,
        /** How much it is used, as people read it. */
        val usage: String = DeploymentUsageText.of(documentsRequested, lastUsedAt),
        /** What a request naming no version gets here. */
        val latest: Boolean = false,
        /** It is the latest because an override names it. */
        val overridden: Boolean = false,
        /** The catalog has an override here, this release or a draft. */
        val catalogOverridden: Boolean = false,
    )

    /** A draft the environment follows, live. */
    data class FollowedDraftView(val catalogKey: String, val catalogName: String, val draftLabel: String, val href: String)

    /** One catalog's deployable releases, newest first, and those the environment serves. */
    data class ReleaseGroupView(
        val catalogKey: String,
        val catalogName: String,
        val versions: List<String>,
        val servedVersions: Set<String>,
    )

    /** One change in the environment's history, and whether its release can be deployed again. */
    data class DeploymentHistoryView(
        val catalogKey: String,
        val catalogName: String,
        val deployed: Boolean,
        /** The change as people read it. */
        val change: String,
        val version: String?,
        val previousVersion: String?,
        val changedAt: OffsetDateTime,
        val changedByName: String?,
        val redeployable: Boolean,
    )

    /** The full-page list model (shared by the newForm / create non-HTMX branches). */
    private fun ModelBuilder.listModel(tenantId: TenantId) {
        "pageTitle" to "Environments - Epistola"
        "tenant" to GetTenant(tenantId.key).query()
        "tenantId" to tenantId.key
        "environments" to ListEnvironments(tenantId = tenantId).query()
    }
}

/** A release an environment uses because [neededBy] (catalog names of what it deploys) are pinned to it. */
data class ImplicitReleaseView(val catalogKey: String, val catalogName: String, val version: String, val neededBy: List<String>)

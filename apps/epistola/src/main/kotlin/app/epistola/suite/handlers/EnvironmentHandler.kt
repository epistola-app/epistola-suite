// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeleteEnvironment
import app.epistola.suite.environments.commands.DeployRelease
import app.epistola.suite.environments.commands.UndeployRelease
import app.epistola.suite.environments.queries.GetEnvironment
import app.epistola.suite.environments.queries.ListDeployableReleases
import app.epistola.suite.environments.queries.ListDeploymentHistory
import app.epistola.suite.environments.queries.ListDeployments
import app.epistola.suite.environments.queries.ListEnvironments
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
     * An environment: the release of each catalog it serves, a way to deploy another, and what it
     * served over time, with each earlier release offered again where it can still be deployed.
     */
    fun detail(request: ServerRequest): ServerResponse = detail(request, error = null)

    private fun detail(request: ServerRequest, error: String?): ServerResponse {
        val tenantId = request.tenantId()
        val environmentId = request.environmentId(tenantId) ?: return ServerResponse.notFound().build()
        val environment = GetEnvironment(environmentId).query() ?: return ServerResponse.notFound().build()

        val deployable = ListDeployableReleases(tenantId.key).query()
        val catalogNames = deployable.associate { it.catalogKey to it.catalogName }
        val served = ListDeployments(tenantId.key, environmentKey = environmentId.key).query()
        val servedVersions = served.associate { it.catalogKey to it.version }
        val deployableSet = deployable.map { it.catalogKey to it.version }.toSet()

        val history = ListDeploymentHistory(environmentId).query().map { entry ->
            DeploymentHistoryView(
                catalogKey = entry.catalogKey.value,
                catalogName = catalogNames[entry.catalogKey] ?: entry.catalogKey.value,
                deployed = entry.action == DeploymentAction.DEPLOYED,
                version = entry.version,
                previousVersion = entry.previousVersion,
                changedAt = entry.changedAt,
                changedByName = entry.changedByName,
                // Offered again only when deploying it would change something and can succeed:
                // the release still kept its content, and the environment serves another one now.
                redeployable = entry.version != null &&
                    (entry.catalogKey to entry.version) in deployableSet &&
                    servedVersions[entry.catalogKey] != entry.version,
            )
        }

        return ServerResponse.ok().page("environments/detail") {
            "pageTitle" to "${environment.name} - Environments - Epistola"
            "tenantId" to tenantId.key
            "environment" to environment
            "error" to error
            "deployments" to served.map { deployment ->
                ServedReleaseView(
                    catalogKey = deployment.catalogKey.value,
                    catalogName = catalogNames[deployment.catalogKey] ?: deployment.catalogKey.value,
                    version = deployment.version,
                    deployedAt = deployment.deployedAt,
                )
            }
            "releaseGroups" to deployable.groupBy { it.catalogKey }.map { (catalogKey, releases) ->
                ReleaseGroupView(
                    catalogKey = catalogKey.value,
                    catalogName = releases.first().catalogName,
                    versions = releases.map { it.version },
                    servedVersion = servedVersions[catalogKey],
                )
            }
            "history" to history
        }
    }

    /** Deploys the chosen release, given as `catalog@version`. Refusals stay on the page. */
    fun deploy(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val environmentId = request.environmentId(tenantId) ?: return ServerResponse.notFound().build()
        val release = request.param("release").orElse("").trim()
        val catalog = release.substringBefore('@', missingDelimiterValue = "")
        val version = release.substringAfter('@', missingDelimiterValue = "")
        val catalogKey = CatalogKey.validateOrNull(catalog)
        if (catalogKey == null || version.isEmpty()) return detail(request, error = "Choose a release to deploy.")
        return try {
            DeployRelease(environmentId, catalogKey, version).execute()
            redirectTo(environmentId)
        } catch (failure: ReleaseNotDeployableException) {
            detail(request, error = failure.message)
        }
    }

    /** Stops the environment serving a catalog. */
    fun undeploy(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val environmentId = request.environmentId(tenantId) ?: return ServerResponse.notFound().build()
        val catalogKey = CatalogKey.validateOrNull(request.pathVariable("catalogId")) ?: return ServerResponse.notFound().build()
        UndeployRelease(environmentId, catalogKey).execute()
        return redirectTo(environmentId)
    }

    private fun redirectTo(environmentId: EnvironmentId): ServerResponse = ServerResponse.status(303)
        .header("Location", "/tenants/${environmentId.tenantKey.value}/environments/${environmentId.key.value}")
        .build()

    /** A catalog the environment serves, and the release. */
    data class ServedReleaseView(
        val catalogKey: String,
        val catalogName: String,
        val version: String,
        val deployedAt: OffsetDateTime,
    )

    /** One catalog's deployable releases, newest first, and the one the environment serves. */
    data class ReleaseGroupView(
        val catalogKey: String,
        val catalogName: String,
        val versions: List<String>,
        val servedVersion: String?,
    )

    /** One change in the environment's history, and whether its release can be deployed again. */
    data class DeploymentHistoryView(
        val catalogKey: String,
        val catalogName: String,
        val deployed: Boolean,
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

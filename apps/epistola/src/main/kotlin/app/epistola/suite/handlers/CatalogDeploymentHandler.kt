// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.documents.EnvironmentNotFoundException
import app.epistola.suite.environments.EnvironmentDeploymentRefusedException
import app.epistola.suite.environments.ReleaseDependenciesMissingException
import app.epistola.suite.environments.ReleaseNotDeployableException
import app.epistola.suite.environments.commands.DeployCatalogRelease
import app.epistola.suite.environments.commands.UndeployCatalogRelease
import app.epistola.suite.environments.queries.ListDraftDeployments
import app.epistola.suite.handlers.CatalogViews.deployments
import app.epistola.suite.htmx.catalog
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.redirect
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.requirePermission
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/** Deploying a catalog's releases to environments, and stopping them there. */
@Component
class CatalogDeploymentHandler {
    // Logs under CatalogHandler, the name these lines had before the handler was split.
    private val logger = LoggerFactory.getLogger(CatalogHandler::class.java)

    /**
     * Deploys a release of this catalog to an environment, beside what it serves. With
     * `includeDependencies`, the releases of other catalogs it renders with are deployed along.
     * Refusals -- the release is gone, the environment is gone, a dependency release is not
     * served -- come back on the catalog's Deployments page with the reason.
     */
    fun deploy(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalog = request.catalog(tenantId)
        val environment = request.param("environmentId").orElse("").trim()
        val version = request.param("version").orElse("").trim()
        if (environment.isEmpty() || version.isEmpty()) return deployments(request, error = "Choose an environment and a release to deploy.")
        val includeDependencies = request.param("includeDependencies").orElse("") == "true"
        // Beside what the environment serves, unless the deployer chose to stop using the others.
        val replace = request.param("replace").orElse("") == "true"
        // A draft (`draft:<id>`) is deployed by its id and served live, beside the releases.
        val draft = version.removePrefix("draft:").takeIf { it != version }
        if (draft != null && !DraftRef.isKey(draft)) return deployments(request, error = "Choose a draft of this catalog to deploy.")
        return try {
            DeployCatalogRelease(EnvironmentId(EnvironmentKey.of(environment), tenantId), catalog, draft ?: version, includeDependencies, replace = replace && draft == null).execute()
            redirect("/tenants/${tenantId.key}/catalogs/${catalog.address}/deployments")
        } catch (failure: EnvironmentDeploymentRefusedException) {
            deployments(request, error = failure.message)
        } catch (failure: ReleaseNotDeployableException) {
            deployments(request, error = failure.message)
        } catch (failure: EnvironmentNotFoundException) {
            deployments(request, error = failure.message)
        } catch (failure: ReleaseDependenciesMissingException) {
            deployments(
                request,
                error = null,
                deployRefusal = DeployRefusalView.of(
                    failure,
                    deployUrl = "/tenants/${tenantId.key.value}/catalogs/${catalog.address}/deployments",
                    fields = mapOf("environmentId" to environment, "version" to version),
                ),
            )
        }
    }

    /** Stops an environment serving one release of this catalog, given as the `version` field. */
    fun undeploy(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalog = request.catalog(tenantId)
        val version = request.param("version").orElse("").trim()
        if (version.isEmpty()) return deployments(request, error = "Choose a release to undeploy.")
        UndeployCatalogRelease(EnvironmentId(EnvironmentKey.of(request.pathVariable("environmentId")), tenantId), catalog, version).execute()
        return redirect("/tenants/${tenantId.key}/catalogs/${catalog.address}/deployments")
    }

    /** Stops an environment serving a draft of this catalog: requests naming no version get its highest release again. */
    fun unfollow(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.ENVIRONMENT_DEPLOY)
        val catalog = request.catalog(request.tenantId())
        val environment = EnvironmentKey.of(request.pathVariable("environmentId"))
        ListDraftDeployments(tenantId.key, catalog, environment).query().singleOrNull()?.let { deployed ->
            UndeployCatalogRelease(EnvironmentId(environment, tenantId), catalog, deployed.draftId).execute()
        }
        return redirect("/tenants/${tenantId.key}/catalogs/${catalog.address}/deployments")
    }
}

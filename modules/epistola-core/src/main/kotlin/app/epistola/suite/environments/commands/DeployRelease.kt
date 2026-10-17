// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments.commands

import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.EnvironmentNotFoundException
import app.epistola.suite.environments.CatalogDeployment
import app.epistola.suite.environments.DeploymentAction
import app.epistola.suite.environments.DeploymentLog
import app.epistola.suite.environments.MissingDependencyReason
import app.epistola.suite.environments.ReleaseDependencies
import app.epistola.suite.environments.ReleaseDependenciesMissingException
import app.epistola.suite.environments.ReleaseNotDeployableException
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Deploys a release of a catalog to an environment: from now on the environment serves it, beside
 * any other releases of the catalog it already serves. Generation and preview naming the environment
 * render the highest release of the catalog it serves, or the highest that satisfies the request's
 * version selector, so deploying a higher release moves such requests to it and undeploying it moves
 * them back. A request already accepted keeps the release it was bound to.
 *
 * Only a release that kept its content can be deployed: one cut before releases retained their
 * content has nothing to render from. Deploying a release the environment already serves changes
 * nothing and logs nothing.
 *
 * A release renders with the releases of other catalogs it was cut with, direct and transitive
 * ([ReleaseDependencies]), so the environment must serve those too. When it does not serve one:
 * - without [includeDependencies], the deployment is refused with [ReleaseDependenciesMissingException]
 *   listing every missing release and why, and nothing is deployed;
 * - with it, every missing release the tenant has with its content is deployed along, in the same
 *   transaction. A release the tenant does not have is not fetched from its registry (this
 *   installation does not install from a registry while deploying), so it is reported `UNAVAILABLE`;
 *   one without content stays `CONTENT_NOT_RETAINED`. It is all or nothing: when any remains,
 *   the deployment is refused with those, and nothing is deployed.
 *
 * Every release deployed is logged in `environment_deployment_log`, in the same transaction.
 *
 * Permission: `ENVIRONMENT_DEPLOY` (contract `deployRelease`).
 *
 * @return the deployment of the release asked for.
 */
data class DeployRelease(
    val environmentId: EnvironmentId,
    val catalogKey: CatalogKey,
    val version: String,
    val includeDependencies: Boolean = false,
) : Command<CatalogDeployment>,
    RequiresPermission {
    override val permission get() = Permission.ENVIRONMENT_DEPLOY
    override val tenantKey get() = environmentId.tenantKey
}

@Component
class DeployReleaseHandler(
    private val jdbi: Jdbi,
    private val dependencies: ReleaseDependencies,
) : CommandHandler<DeployRelease, CatalogDeployment> {
    override fun handle(command: DeployRelease): CatalogDeployment = jdbi.inTransaction<CatalogDeployment, Exception> { handle ->
        val tenantKey = command.environmentId.tenantKey
        val environmentKey = command.environmentId.key
        // Locked, so deploys and undeploys on one environment run one after the other: the
        // dependency check below holds until this transaction commits.
        val environmentExists = handle.createQuery("SELECT id FROM environments WHERE tenant_key = :t AND id = :e FOR UPDATE")
            .bind("t", tenantKey)
            .bind("e", environmentKey)
            .mapTo<String>()
            .findOne()
            .isPresent
        if (!environmentExists) throw EnvironmentNotFoundException(tenantKey, environmentKey)

        requireDeployable(handle, tenantKey, command.catalogKey, command.version)
        served(handle, command, command.catalogKey, command.version)?.let { return@inTransaction it }

        val missing = dependencies.missing(handle, tenantKey, environmentKey, command.catalogKey, command.version)
        if (missing.isNotEmpty()) {
            if (!command.includeDependencies) {
                throw ReleaseDependenciesMissingException(environmentKey, command.catalogKey, command.version, missing, includedDependencies = false)
            }
            val blocking = missing
                .filter { it.reason != MissingDependencyReason.NOT_DEPLOYED }
                .map { if (it.reason == MissingDependencyReason.NOT_INSTALLED) it.copy(reason = MissingDependencyReason.UNAVAILABLE) else it }
            if (blocking.isNotEmpty()) {
                throw ReleaseDependenciesMissingException(environmentKey, command.catalogKey, command.version, blocking, includedDependencies = true)
            }
            missing.forEach { deploy(handle, command, it.tenantCatalogKey, it.releaseVersion) }
        }
        deploy(handle, command, command.catalogKey, command.version)
    }

    /** Refuses a release that does not exist or kept no content to render from. */
    private fun requireDeployable(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String) {
        val retained = handle.createQuery(
            "SELECT content_retained FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :v",
        )
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .bind("v", version)
            .mapTo<Boolean>()
            .findOne()
            .orElse(null)
            ?: throw ReleaseNotDeployableException(catalogKey, version, "no such release")
        if (!retained) {
            throw ReleaseNotDeployableException(catalogKey, version, "it kept no content to render from")
        }
    }

    /** The deployment, when the environment already serves this release. */
    private fun served(handle: Handle, command: DeployRelease, catalogKey: CatalogKey, version: String): CatalogDeployment? = handle.createQuery(
        """
        SELECT d.environment_key, cat.catalog_key AS catalog_key, d.version, d.deployed_at, d.deployed_by
        FROM environment_catalog_deployments d
        JOIN catalogs cat ON cat.tenant_key = d.tenant_key AND cat.catalog_id = d.catalog_id
        WHERE d.tenant_key = :t AND d.environment_key = :e AND d.catalog_id = ${catalogAt("t", "c")} AND d.version = :v
        """,
    )
        .bind("t", command.environmentId.tenantKey)
        .bind("e", command.environmentId.key)
        .bind("c", catalogKey)
        .bind("v", version)
        .mapTo<CatalogDeployment>()
        .findOne()
        .orElse(null)

    /** Serves the release in the environment and logs it. */
    private fun deploy(handle: Handle, command: DeployRelease, catalogKey: CatalogKey, version: String): CatalogDeployment {
        val tenantKey = command.environmentId.tenantKey
        val deployment = handle.createQuery(
            """
            INSERT INTO environment_catalog_deployments (tenant_key, environment_key, catalog_id, version, deployed_at, deployed_by)
            VALUES (:t, :e, ${catalogAt("t", "c")}, :v, NOW(), :by)
            RETURNING environment_key,
                      (SELECT catalog_key FROM catalogs cat
                       WHERE cat.tenant_key = environment_catalog_deployments.tenant_key
                         AND cat.catalog_id = environment_catalog_deployments.catalog_id) AS catalog_key,
                      version, deployed_at, deployed_by
            """,
        )
            .bind("t", tenantKey)
            .bind("e", command.environmentId.key)
            .bind("c", catalogKey)
            .bind("v", version)
            .bind("by", currentUserIdOrNull()?.value)
            .mapTo<CatalogDeployment>()
            .one()
        DeploymentLog.record(handle, tenantKey, command.environmentId.key, catalogKey, DeploymentAction.DEPLOYED, version)
        return deployment
    }
}

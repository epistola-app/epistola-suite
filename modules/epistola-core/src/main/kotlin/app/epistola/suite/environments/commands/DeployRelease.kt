// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments.commands

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.documents.EnvironmentNotFoundException
import app.epistola.suite.environments.CatalogDeployment
import app.epistola.suite.environments.DeploymentAction
import app.epistola.suite.environments.DeploymentLog
import app.epistola.suite.environments.ReleaseNotDeployableException
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Deploys a release of a catalog to an environment: from now on, generation and preview in that
 * environment render every template of the catalog from this release.
 *
 * Replaces whatever release of the catalog the environment served before, so promoting is deploying
 * the release another environment runs and rolling back is deploying an earlier one. A request
 * already accepted keeps the release it was bound to.
 *
 * Only a release that kept its content can be deployed: one cut before releases retained their
 * content has nothing to render from.
 *
 * Every change is logged in `environment_deployment_log`, in the same transaction, with the release
 * it replaced. Deploying the release the environment already serves changes nothing and logs nothing.
 *
 * Permission: `TEMPLATE_PUBLISH`, the permission per-template activation needed. A dedicated deploy
 * permission is planned with the 2.0 permission changes (D13).
 */
data class DeployRelease(
    val environmentId: EnvironmentId,
    val catalogKey: CatalogKey,
    val version: String,
) : Command<CatalogDeployment>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_PUBLISH
    override val tenantKey get() = environmentId.tenantKey
}

@Component
class DeployReleaseHandler(
    private val jdbi: Jdbi,
) : CommandHandler<DeployRelease, CatalogDeployment> {
    override fun handle(command: DeployRelease): CatalogDeployment = jdbi.inTransaction<CatalogDeployment, Exception> { handle ->
        val tenantKey = command.environmentId.tenantKey
        val environmentExists = handle.createQuery("SELECT EXISTS (SELECT 1 FROM environments WHERE tenant_key = :t AND id = :e)")
            .bind("t", tenantKey)
            .bind("e", command.environmentId.key)
            .mapTo<Boolean>()
            .one()
        if (!environmentExists) throw EnvironmentNotFoundException(tenantKey, command.environmentId.key)

        val retained = handle.createQuery(
            "SELECT content_retained FROM catalog_releases WHERE tenant_key = :t AND catalog_key = :c AND version = :v",
        )
            .bind("t", tenantKey)
            .bind("c", command.catalogKey)
            .bind("v", command.version)
            .mapTo<Boolean>()
            .findOne()
            .orElse(null)
            ?: throw ReleaseNotDeployableException(command.catalogKey, command.version, "no such release")
        if (!retained) {
            throw ReleaseNotDeployableException(command.catalogKey, command.version, "it kept no content to render from")
        }

        // Locked, so two deploys racing on one environment and catalog log the release each replaced.
        val current = handle.createQuery(
            """
            SELECT environment_key, catalog_key, version, deployed_at, deployed_by
            FROM environment_catalog_deployments
            WHERE tenant_key = :t AND environment_key = :e AND catalog_key = :c
            FOR UPDATE
            """,
        )
            .bind("t", tenantKey)
            .bind("e", command.environmentId.key)
            .bind("c", command.catalogKey)
            .mapTo<CatalogDeployment>()
            .findOne()
            .orElse(null)
        if (current?.version == command.version) return@inTransaction current

        val deployment = handle.createQuery(
            """
            INSERT INTO environment_catalog_deployments (tenant_key, environment_key, catalog_key, version, deployed_at, deployed_by)
            VALUES (:t, :e, :c, :v, NOW(), :by)
            ON CONFLICT (tenant_key, environment_key, catalog_key)
            DO UPDATE SET version = EXCLUDED.version, deployed_at = EXCLUDED.deployed_at, deployed_by = EXCLUDED.deployed_by
            RETURNING environment_key, catalog_key, version, deployed_at, deployed_by
            """,
        )
            .bind("t", tenantKey)
            .bind("e", command.environmentId.key)
            .bind("c", command.catalogKey)
            .bind("v", command.version)
            .bind("by", currentUserIdOrNull()?.value)
            .mapTo<CatalogDeployment>()
            .one()
        DeploymentLog.record(handle, tenantKey, command.environmentId.key, command.catalogKey, DeploymentAction.DEPLOYED, command.version, current?.version)
        deployment
    }
}

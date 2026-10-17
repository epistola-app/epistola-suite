// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments.commands

import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.environments.DeploymentAction
import app.epistola.suite.environments.DeploymentLog
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Stops an environment serving one release of a catalog. Generation naming that environment then
 * renders the highest release of the catalog it still serves, and fails for the catalog's templates
 * when it serves none.
 *
 * Logged in `environment_deployment_log` with the release it stopped serving.
 *
 * Another release the environment serves may render with this one (contract ADR 0003 leaves open
 * whether undeploying it should be refused); it is not refused.
 *
 * @return whether the environment served the release.
 */
data class UndeployRelease(
    val environmentId: EnvironmentId,
    val catalogKey: CatalogKey,
    val version: String,
) : Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.ENVIRONMENT_DEPLOY
    override val tenantKey get() = environmentId.tenantKey
}

@Component
class UndeployReleaseHandler(
    private val jdbi: Jdbi,
) : CommandHandler<UndeployRelease, Boolean> {
    override fun handle(command: UndeployRelease): Boolean = jdbi.inTransaction<Boolean, Exception> { handle ->
        // Taken in the order DeployRelease takes it, so a deploy checking its dependencies sees this
        // undeploy before or after, never half-way.
        handle.createQuery("SELECT id FROM environments WHERE tenant_key = :t AND id = :e FOR UPDATE")
            .bind("t", command.environmentId.tenantKey)
            .bind("e", command.environmentId.key)
            .mapTo(String::class.java)
            .findOne()
        val removed = handle.createUpdate(
            """
            DELETE FROM environment_catalog_deployments
            WHERE tenant_key = :t AND environment_key = :e AND catalog_id = ${catalogAt("t", "c")} AND version = :v
            """,
        )
            .bind("t", command.environmentId.tenantKey)
            .bind("e", command.environmentId.key)
            .bind("c", command.catalogKey)
            .bind("v", command.version)
            .execute() > 0
        if (!removed) return@inTransaction false
        DeploymentLog.record(
            handle,
            command.environmentId.tenantKey,
            command.environmentId.key,
            command.catalogKey,
            DeploymentAction.UNDEPLOYED,
            command.version,
        )
        true
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments.commands

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Stops an environment serving a catalog. Generation naming that environment then fails for the
 * catalog's templates until a release is deployed again.
 *
 * @return whether the environment served the catalog.
 */
data class UndeployRelease(
    val environmentId: EnvironmentId,
    val catalogKey: CatalogKey,
) : Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_PUBLISH
    override val tenantKey get() = environmentId.tenantKey
}

@Component
class UndeployReleaseHandler(
    private val jdbi: Jdbi,
) : CommandHandler<UndeployRelease, Boolean> {
    override fun handle(command: UndeployRelease): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createUpdate(
            "DELETE FROM environment_catalog_deployments WHERE tenant_key = :t AND environment_key = :e AND catalog_key = :c",
        )
            .bind("t", command.environmentId.tenantKey)
            .bind("e", command.environmentId.key)
            .bind("c", command.catalogKey)
            .execute() > 0
    }
}

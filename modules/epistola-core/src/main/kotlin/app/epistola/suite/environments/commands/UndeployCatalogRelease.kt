// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments.commands

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.releaseAt
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.environments.DeploymentAction
import app.epistola.suite.environments.DeploymentLog
import app.epistola.suite.environments.DraftDeploymentStore
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Stops an environment serving one version of a catalog (contract `undeployCatalogVersion`): a
 * release by its number or `versionId`, or the draft it serves by its `versionId`. A request naming
 * no version then renders the highest release it still serves.
 *
 * For a release: Generation naming that environment then
 * renders the highest release of the catalog it still serves, and fails for the catalog's templates
 * when it serves none.
 *
 * Logged in `environment_deployment_log` with the release it stopped serving.
 *
 * Only an explicit deployment is removed. It is never refused because another release the
 * environment serves renders with this one: that release keeps using it implicitly (contract ADR
 * 0003). A release used only implicitly is not deployed, so undeploying it finds nothing.
 *
 * @return whether the environment served the release.
 */
data class UndeployCatalogRelease(
    val environmentId: EnvironmentId,
    /** The catalog, in [environmentId]'s tenant. */
    val catalog: CatalogId,
    val version: String,
) : Command<Boolean>,
    RequiresPermission {
    init {
        validate("catalog", catalog.tenantKey == environmentId.tenantKey) { "The catalog and the environment are of one tenant" }
    }

    override val permission get() = Permission.ENVIRONMENT_DEPLOY
    override val tenantKey get() = environmentId.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
}

@Component
class UndeployCatalogReleaseHandler(
    private val jdbi: Jdbi,
) : CommandHandler<UndeployCatalogRelease, Boolean> {
    override fun handle(command: UndeployCatalogRelease): Boolean = jdbi.inTransaction<Boolean, Exception> { handle ->
        // Taken in the order DeployCatalogRelease takes it, so a deploy checking its dependencies sees this
        // undeploy before or after, never half-way.
        handle.createQuery("SELECT id FROM environments WHERE tenant_key = :t AND id = :e FOR UPDATE")
            .bind("t", command.environmentId.tenantKey)
            .bind("e", command.environmentId.key)
            .mapTo(String::class.java)
            .findOne()
        // The draft it serves, by its id.
        val draft = DraftDeploymentStore.list(handle, command.environmentId.tenantKey, command.environmentId.key, command.catalog).singleOrNull()
        if (draft != null && draft.draftId == command.version) {
            return@inTransaction DraftDeploymentStore.undeploy(handle, command.environmentId.key, command.catalog) != null
        }
        // A release, by its number or its id.
        val version = handle.createQuery(
            "SELECT version FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND (version = :v OR version_key = :v)",
        )
            .bind("t", command.environmentId.tenantKey)
            .bindCatalog("c", command.catalog)
            .bind("v", command.version)
            .mapTo(String::class.java)
            .findOne()
            .orElse(command.version)
        val removed = handle.createUpdate(
            """
            DELETE FROM environment_catalog_deployments
            WHERE tenant_key = :t AND environment_key = :e AND target_version_id = ${releaseAt("t", "c", "v")}
            """,
        )
            .bind("t", command.environmentId.tenantKey)
            .bind("e", command.environmentId.key)
            .bindCatalog("c", command.catalog)
            .bind("v", version)
            .execute() > 0
        if (!removed) return@inTransaction false
        DeploymentLog.record(
            handle,
            command.environmentId.key,
            command.catalog,
            DeploymentAction.UNDEPLOYED,
            version,
        )
        true
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.security.currentUserIdOrNull
import org.jdbi.v3.core.Handle
import java.time.OffsetDateTime

/** What happened to the release an environment serves for a catalog. */
enum class DeploymentAction { DEPLOYED, UNDEPLOYED }

/**
 * One change to what an environment serves.
 *
 * @property version the release served after the change; null when the catalog was undeployed.
 * @property previousVersion the release served before it; null when nothing was.
 * @property changedByName the display name of who made the change; null for a system change or a
 *   user since removed.
 */
data class DeploymentLogEntry(
    val environmentKey: EnvironmentKey,
    val catalogKey: CatalogKey,
    val action: DeploymentAction,
    val version: String?,
    val previousVersion: String?,
    val changedAt: OffsetDateTime,
    val changedByName: String?,
)

/**
 * Writes `environment_deployment_log`. Called by the deploy and undeploy commands inside their own
 * transaction, so the history and the current state cannot disagree.
 */
internal object DeploymentLog {
    fun record(
        handle: Handle,
        tenantKey: TenantKey,
        environmentKey: EnvironmentKey,
        catalogKey: CatalogKey,
        action: DeploymentAction,
        version: String?,
        previousVersion: String?,
    ) {
        handle.createUpdate(
            """
            INSERT INTO environment_deployment_log
                (tenant_key, environment_key, catalog_key, action, version, previous_version, changed_at, changed_by)
            VALUES (:t, :e, :c, :action, :v, :previous, NOW(), :by)
            """,
        )
            .bind("t", tenantKey)
            .bind("e", environmentKey)
            .bind("c", catalogKey)
            .bind("action", action.name)
            .bind("v", version)
            .bind("previous", previousVersion)
            .bind("by", currentUserIdOrNull()?.value)
            .execute()
    }
}

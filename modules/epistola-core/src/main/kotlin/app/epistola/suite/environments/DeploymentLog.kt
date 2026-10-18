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

/** What happened to one release an environment serves. */
enum class DeploymentAction {
    DEPLOYED,
    UNDEPLOYED,

    /** An override was set: the environment uses [DeploymentLogEntry.version] or the draft [DeploymentLogEntry.draftLabel] as its latest. */
    OVERRIDE_SET,

    /** An override was cleared: the environment uses the highest release it serves again. */
    OVERRIDE_CLEARED,
}

/**
 * One change to what an environment serves: one release of a catalog deployed or undeployed, or an
 * override set or cleared. An environment serves any number of releases of a catalog, so a deploy
 * replaces nothing.
 *
 * @property version the release deployed or undeployed, or the release an override used; null for
 *   an override naming a draft.
 * @property draftLabel for an override naming a draft, the draft's name then; null otherwise.
 * @property previousVersion for a deploy logged while an environment served one release per catalog
 *   (before 2.0), the release it replaced; null otherwise.
 * @property changedByName the display name of who made the change; null for a system change or a
 *   user since removed.
 * @property registry the catalog's registry when the change was made (contract ADR 0003).
 * @property namespace the catalog's namespace when the change was made.
 */
data class DeploymentLogEntry(
    val environmentKey: EnvironmentKey,
    val catalogKey: CatalogKey,
    val action: DeploymentAction,
    val version: String?,
    val previousVersion: String?,
    val changedAt: OffsetDateTime,
    val changedByName: String?,
    val registry: String? = null,
    val namespace: String? = null,
    val draftLabel: String? = null,
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
        /** The version a deploy replaced, so the history reads "made v1.1.0 live, replacing v1.0.0". */
        previousVersion: String? = null,
        /** For an override naming a draft: the draft's name. */
        draftLabel: String? = null,
    ) {
        // clock_timestamp(), not NOW(): one command can log several changes (an undeploy and the
        // override it ends), and the history lists them in the order they happened.
        handle.createUpdate(
            """
            INSERT INTO environment_deployment_log
                (tenant_key, environment_key, catalog_key, registry, namespace, action, version, previous_version, draft_label, changed_at, changed_by)
            VALUES (
                :t, :e, :c,
                (SELECT registry FROM catalogs WHERE tenant_key = :t AND catalog_key = :c),
                (SELECT namespace FROM catalogs WHERE tenant_key = :t AND catalog_key = :c),
                :action, :v, :previous, :draft, clock_timestamp(), :by
            )
            """,
        )
            .bind("t", tenantKey)
            .bind("e", environmentKey)
            .bind("c", catalogKey)
            .bind("action", action.name)
            .bind("v", version)
            .bind("previous", previousVersion)
            .bind("draft", draftLabel)
            .bind("by", currentUserIdOrNull()?.value)
            .execute()
    }
}

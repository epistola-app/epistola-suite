// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.security.currentUserIdOrNull
import org.jdbi.v3.core.Handle
import java.time.OffsetDateTime

/** What happened to one catalog version an environment serves (contract `DeploymentAction`). */
enum class DeploymentAction {
    DEPLOYED,
    UNDEPLOYED,

    /** A deployed draft was released: the environment serves that release, [DeploymentLogEntry.version], instead. */
    RELEASED,
}

/**
 * One change to what an environment serves: a release or a draft of a catalog deployed or
 * undeployed, or a deployed draft released. An environment serves any number of releases of a
 * catalog, so deploying one replaces nothing.
 *
 * @property version the release deployed, undeployed or released; null for a draft deployed or undeployed.
 * @property draftLabel for a draft, its name then; null for a release.
 * @property versionKey the version's public id then (contract `versionId`); null for entries before it was recorded.
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
    val versionKey: String? = null,
)

/**
 * Writes `environment_deployment_log`. Called by the deploy and undeploy commands inside their own
 * transaction, so the history and the current state cannot disagree.
 */
internal object DeploymentLog {
    fun record(
        handle: Handle,
        environmentKey: EnvironmentKey,
        catalog: CatalogId,
        action: DeploymentAction,
        version: String?,
        /** The version a deploy replaced, so the history reads "made v1.1.0 live, replacing v1.0.0". */
        previousVersion: String? = null,
        /** For a draft: its name. */
        draftLabel: String? = null,
        /** The version's public id; for a release looked up from its number when not given. */
        versionKey: String? = null,
    ) {
        // clock_timestamp(), not NOW(): one command can log several changes (a draft replaced and the
        // one deployed in its place), and the history lists them in the order they happened.
        handle.createUpdate(
            """
            INSERT INTO environment_deployment_log
                (tenant_key, environment_key, catalog_key, registry, namespace, action, version, previous_version, draft_label, version_key, changed_at, changed_by)
            VALUES (
                :t, :e, :c,
                (SELECT registry FROM catalogs WHERE tenant_key = :t AND catalog_id = epistola_catalog_id(:t, :c_ns, :c)),
                (SELECT namespace FROM catalogs WHERE tenant_key = :t AND catalog_id = epistola_catalog_id(:t, :c_ns, :c)),
                :action, :v, :previous, :draft,
                COALESCE(CAST(:key AS VARCHAR), (SELECT r.version_key FROM catalog_releases r WHERE r.tenant_key = :t AND r.catalog_id = ${catalogAt("t", "c")} AND r.version = :v)),
                clock_timestamp(), :by
            )
            """,
        )
            .bind("t", catalog.tenantKey)
            .bind("e", environmentKey)
            .bindCatalog("c", catalog)
            .bind("action", action.name)
            .bind("v", version)
            .bind("previous", previousVersion)
            .bind("draft", draftLabel)
            .bind("key", versionKey)
            .bind("by", currentUserIdOrNull()?.value)
            .execute()
    }
}

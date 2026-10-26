// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.releases.CatalogDraft
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.EnvironmentNotFoundException
import org.jdbi.v3.core.Handle
import java.util.UUID

/** Refused: a deployment that would make an environment serve what it may not, such as a draft in production. */
class EnvironmentDeploymentRefusedException(message: String) : RuntimeException(message)

/**
 * A draft an environment serves, rendered live (contract ADR 0003, "Deploying"): at most one per
 * catalog per environment, never in production. A request through the environment that names no
 * version renders it.
 *
 * @property draftId the draft's public id (contract `versionId`).
 * @property draftLabel its name as people see it ("Next version" for the next version).
 */
data class DraftDeployment(
    val environmentKey: EnvironmentKey,
    val catalog: CatalogId,
    val draftId: String,
    val draftLabel: String,
) {
    val catalogKey: CatalogKey get() = catalog.key
    val namespace: String get() = catalog.namespace
}

/**
 * The SQL of deployed drafts, which the deploy commands, the draft lifecycle and generation share.
 * Every change it makes is logged in the environment's history in the caller's transaction.
 */
object DraftDeploymentStore {

    /** Locks the environment row, as deploys do, and says whether it is production. */
    internal fun lockEnvironment(handle: Handle, tenantKey: TenantKey, environmentKey: EnvironmentKey): Boolean = handle.createQuery(
        "SELECT production FROM environments WHERE tenant_key = :t AND id = :e FOR UPDATE",
    )
        .bind("t", tenantKey).bind("e", environmentKey)
        .mapTo(Boolean::class.java)
        .findOne()
        .orElseThrow { EnvironmentNotFoundException(tenantKey, environmentKey) }

    /** The internal id and label of the draft of [catalogKey] that [ref] names, or null when there is none. */
    internal fun draft(handle: Handle, catalog: CatalogId, ref: DraftRef): Pair<UUID, String>? {
        val match = when (ref) {
            DraftRef.Next -> "v.is_next"
            is DraftRef.Id -> "v.version_key = :id"
            is DraftRef.Released -> return null
        }
        return handle.createQuery(
            """
            SELECT v.version_id, $LABEL AS label FROM catalog_versions v
            WHERE v.tenant_key = :t AND v.catalog_id = ${catalogAt("t", "c")} AND v.state = 'draft' AND $match
            """,
        )
            .bind("t", catalog.tenantKey).bindCatalog("c", catalog)
            .also { q -> (ref as? DraftRef.Id)?.let { q.bind("id", it.key) } }
            .map { rs, _ -> rs.getObject("version_id", UUID::class.java) to rs.getString("label") }
            .findOne()
            .orElse(null)
    }

    /** The drafts [environmentKey] (or every environment) serves, of [catalog] or every catalog. */
    fun list(handle: Handle, tenantKey: TenantKey, environmentKey: EnvironmentKey?, catalog: CatalogId?): List<DraftDeployment> = rows(
        handle,
        tenantKey,
        "${if (environmentKey != null) "AND d.environment_key = :e" else ""} ${if (catalog != null) "AND c.catalog_id = epistola_catalog_id(:t, :c_ns, :c)" else ""}",
    ) { q ->
        environmentKey?.let { q.bind("e", it) }
        catalog?.let { q.bindCatalog("c", it) }
    }

    /**
     * Stops serving the draft [environmentKey] serves of [catalogKey], logged as undeployed; null
     * when it serves none.
     */
    internal fun undeploy(handle: Handle, environmentKey: EnvironmentKey, catalog: CatalogId): DraftDeployment? {
        val tenantKey = catalog.tenantKey
        val served = list(handle, tenantKey, environmentKey, catalog).singleOrNull() ?: return null
        handle.createUpdate(
            """
            DELETE FROM environment_catalog_deployments d
            USING catalog_versions v
            WHERE d.tenant_key = :t AND d.environment_key = :e AND d.catalog_id = ${catalogAt("t", "c")}
              AND v.tenant_key = d.tenant_key AND v.version_id = d.target_version_id AND v.state = 'draft'
            """,
        ).bind("t", tenantKey).bind("e", environmentKey).bindCatalog("c", catalog).execute()
        log(handle, tenantKey, served, DeploymentAction.UNDEPLOYED, version = null)
        return served
    }

    /** Every environment serving draft [versionId] stops, each logged as undeployed: the draft is discarded. */
    internal fun endFor(handle: Handle, tenantKey: TenantKey, versionId: UUID) {
        val served = byDraft(handle, tenantKey, versionId)
        handle.createUpdate("DELETE FROM environment_catalog_deployments WHERE tenant_key = :t AND target_version_id = :d")
            .bind("t", tenantKey).bind("d", versionId).execute()
        served.forEach { log(handle, tenantKey, it, DeploymentAction.UNDEPLOYED, version = null) }
    }

    /**
     * The environments serving draft [versionId], which is about to be released. A deployment names
     * the row (ADR 0036), so once the row is frozen they serve the release with nothing to update
     * (contract ADR 0003); call [released] with the result after freezing, to log each as released.
     */
    internal fun servingDraft(handle: Handle, tenantKey: TenantKey, versionId: UUID): List<DraftDeployment> = byDraft(handle, tenantKey, versionId)

    /**
     * Logs each of [served], the deployments of a draft that was just released as [version], as
     * released, and restarts their deployment time: from now on they serve the release.
     */
    internal fun released(handle: Handle, tenantKey: TenantKey, versionId: UUID, served: List<DraftDeployment>, version: String) {
        if (served.isEmpty()) return
        handle.createUpdate("UPDATE environment_catalog_deployments SET deployed_at = NOW() WHERE tenant_key = :t AND target_version_id = :d")
            .bind("t", tenantKey).bind("d", versionId).execute()
        served.forEach { log(handle, tenantKey, it, DeploymentAction.RELEASED, version = version) }
    }

    /** Every environment serving a draft of [catalog] stops, each logged as undeployed: the catalog is deleted. */
    internal fun endForCatalog(handle: Handle, catalog: CatalogId) {
        val served = list(handle, catalog.tenantKey, null, catalog)
        handle.createUpdate(
            """
            DELETE FROM environment_catalog_deployments d
            USING catalog_versions v
            WHERE d.tenant_key = :t AND d.catalog_id = ${catalogAt("t", "c")}
              AND v.tenant_key = d.tenant_key AND v.version_id = d.target_version_id AND v.state = 'draft'
            """,
        ).bind("t", catalog.tenantKey).bindCatalog("c", catalog).execute()
        served.forEach { log(handle, catalog.tenantKey, it, DeploymentAction.UNDEPLOYED, version = null) }
    }

    /** Whether [environmentKey] serves a draft of any catalog. */
    internal fun servesDraft(handle: Handle, tenantKey: TenantKey, environmentKey: EnvironmentKey): Boolean = list(handle, tenantKey, environmentKey, null).isNotEmpty()

    private fun byDraft(handle: Handle, tenantKey: TenantKey, versionId: UUID): List<DraftDeployment> = rows(handle, tenantKey, "AND d.target_version_id = :d") { it.bind("d", versionId) }

    private fun log(handle: Handle, tenantKey: TenantKey, deployment: DraftDeployment, action: DeploymentAction, version: String?) = DeploymentLog.record(
        handle,
        deployment.environmentKey,
        deployment.catalog,
        action,
        version,
        draftLabel = deployment.draftLabel,
        versionKey = deployment.draftId,
    )

    private fun rows(handle: Handle, tenantKey: TenantKey, filter: String, bind: (org.jdbi.v3.core.statement.Query) -> Unit): List<DraftDeployment> = handle.createQuery(
        """
        SELECT d.environment_key, c.catalog_key, c.namespace, v.version_key, $LABEL AS label
        FROM environment_catalog_deployments d
        JOIN catalogs c ON c.tenant_key = d.tenant_key AND c.catalog_id = d.catalog_id
        JOIN catalog_versions v ON v.tenant_key = d.tenant_key AND v.version_id = d.target_version_id AND v.state = 'draft'
        WHERE d.tenant_key = :t $filter
        ORDER BY d.environment_key, c.catalog_key, c.namespace
        """,
    )
        .bind("t", tenantKey)
        .also(bind)
        .map { rs, _ ->
            DraftDeployment(
                environmentKey = EnvironmentKey.of(rs.getString("environment_key")),
                catalog = CatalogId(rs.getString("namespace"), CatalogKey.of(rs.getString("catalog_key")), TenantId(tenantKey)),
                draftId = rs.getString("version_key"),
                draftLabel = rs.getString("label"),
            )
        }
        .list()

    /** A draft's label in SQL, over alias `v`: its name, or "Next release" for the next release. */
    private val LABEL = "CASE WHEN v.is_next THEN '${CatalogDraft.NEXT_LABEL}' ELSE v.name END"
}

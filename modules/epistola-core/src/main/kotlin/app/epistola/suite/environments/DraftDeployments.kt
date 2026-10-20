// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.versions.CatalogDraft
import app.epistola.suite.catalog.versions.DraftRef
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
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
    val catalogKey: CatalogKey,
    val draftId: String,
    val draftLabel: String,
)

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
    internal fun draft(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, ref: DraftRef): Pair<UUID, String>? {
        val match = when (ref) {
            DraftRef.Next -> "v.is_next"
            is DraftRef.Id -> "v.version_key = :id"
        }
        return handle.createQuery(
            """
            SELECT v.version_id, $LABEL AS label FROM catalog_versions v
            WHERE v.tenant_key = :t AND v.catalog_id = ${catalogAt("t", "c")} AND v.kind = 'draft' AND $match
            """,
        )
            .bind("t", tenantKey).bind("c", catalogKey)
            .also { q -> (ref as? DraftRef.Id)?.let { q.bind("id", it.key) } }
            .map { rs, _ -> rs.getObject("version_id", UUID::class.java) to rs.getString("label") }
            .findOne()
            .orElse(null)
    }

    /** The drafts [environmentKey] (or every environment) serves, of [catalogKey] or every catalog. */
    fun list(handle: Handle, tenantKey: TenantKey, environmentKey: EnvironmentKey?, catalogKey: CatalogKey?): List<DraftDeployment> = rows(
        handle,
        tenantKey,
        "${if (environmentKey != null) "AND d.environment_key = :e" else ""} ${if (catalogKey != null) "AND c.catalog_key = :c" else ""}",
    ) { q ->
        environmentKey?.let { q.bind("e", it) }
        catalogKey?.let { q.bind("c", it) }
    }

    /**
     * Stops serving the draft [environmentKey] serves of [catalogKey], logged as undeployed; null
     * when it serves none.
     */
    internal fun undeploy(handle: Handle, tenantKey: TenantKey, environmentKey: EnvironmentKey, catalogKey: CatalogKey): DraftDeployment? {
        val served = list(handle, tenantKey, environmentKey, catalogKey).singleOrNull() ?: return null
        handle.createUpdate(
            """
            DELETE FROM environment_catalog_deployments
            WHERE tenant_key = :t AND environment_key = :e AND catalog_id = ${catalogAt("t", "c")} AND draft_id IS NOT NULL
            """,
        ).bind("t", tenantKey).bind("e", environmentKey).bind("c", catalogKey).execute()
        log(handle, tenantKey, served, DeploymentAction.UNDEPLOYED, version = null)
        return served
    }

    /** Every environment serving draft [versionId] stops, each logged as undeployed: the draft is discarded. */
    internal fun endFor(handle: Handle, tenantKey: TenantKey, versionId: UUID) {
        val served = byDraft(handle, tenantKey, versionId)
        handle.createUpdate("DELETE FROM environment_catalog_deployments WHERE tenant_key = :t AND draft_id = :d")
            .bind("t", tenantKey).bind("d", versionId).execute()
        served.forEach { log(handle, tenantKey, it, DeploymentAction.UNDEPLOYED, version = null) }
    }

    /**
     * Every environment serving draft [versionId] serves release [version] instead, which keeps the
     * draft's id: the draft was released. Each is logged as released.
     */
    internal fun releaseFor(handle: Handle, tenantKey: TenantKey, versionId: UUID, version: String) {
        val served = byDraft(handle, tenantKey, versionId)
        handle.createUpdate(
            "UPDATE environment_catalog_deployments SET version = :v, draft_id = NULL, deployed_at = NOW() WHERE tenant_key = :t AND draft_id = :d",
        ).bind("t", tenantKey).bind("d", versionId).bind("v", version).execute()
        served.forEach { log(handle, tenantKey, it, DeploymentAction.RELEASED, version = version) }
    }

    /** Whether [environmentKey] serves a draft of any catalog. */
    internal fun servesDraft(handle: Handle, tenantKey: TenantKey, environmentKey: EnvironmentKey): Boolean = list(handle, tenantKey, environmentKey, null).isNotEmpty()

    private fun byDraft(handle: Handle, tenantKey: TenantKey, versionId: UUID): List<DraftDeployment> = rows(handle, tenantKey, "AND d.draft_id = :d") { it.bind("d", versionId) }

    private fun log(handle: Handle, tenantKey: TenantKey, deployment: DraftDeployment, action: DeploymentAction, version: String?) = DeploymentLog.record(
        handle,
        tenantKey,
        deployment.environmentKey,
        deployment.catalogKey,
        action,
        version,
        draftLabel = deployment.draftLabel,
        versionKey = deployment.draftId,
    )

    private fun rows(handle: Handle, tenantKey: TenantKey, filter: String, bind: (org.jdbi.v3.core.statement.Query) -> Unit): List<DraftDeployment> = handle.createQuery(
        """
        SELECT d.environment_key, c.catalog_key, v.version_key, $LABEL AS label
        FROM environment_catalog_deployments d
        JOIN catalogs c ON c.tenant_key = d.tenant_key AND c.catalog_id = d.catalog_id
        JOIN catalog_versions v ON v.tenant_key = d.tenant_key AND v.version_id = d.draft_id
        WHERE d.tenant_key = :t AND d.draft_id IS NOT NULL $filter
        ORDER BY d.environment_key, c.catalog_key
        """,
    )
        .bind("t", tenantKey)
        .also(bind)
        .map { rs, _ ->
            DraftDeployment(
                environmentKey = EnvironmentKey.of(rs.getString("environment_key")),
                catalogKey = CatalogKey.of(rs.getString("catalog_key")),
                draftId = rs.getString("version_key"),
                draftLabel = rs.getString("label"),
            )
        }
        .list()

    /** A draft's label in SQL, over alias `v`: its name, or "Next version" for the next version. */
    private val LABEL = "CASE WHEN v.is_next THEN '${CatalogDraft.NEXT_LABEL}' ELSE v.name END"
}

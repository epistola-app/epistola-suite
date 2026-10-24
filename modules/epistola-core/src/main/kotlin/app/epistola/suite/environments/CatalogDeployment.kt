// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.releases.CatalogDraft
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.kotlin.mapTo
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A catalog version an environment serves (contract `CatalogDeployment`): a release, or a draft,
 * rendered live.
 *
 * @property version the release's number; null for a draft.
 * @property versionId the version's public id: the draft's, or the release's.
 * @property draftName the draft's name as people see it; null for a release.
 */
data class CatalogDeployment(
    val environmentKey: EnvironmentKey,
    val catalogKey: CatalogKey,
    val version: String?,
    val versionId: String?,
    val draftName: String?,
    val deployedAt: OffsetDateTime,
    val deployedBy: UserKey?,
    /** Generation requests through the environment accepted for this version since it was deployed. */
    val documentsRequested: Long = 0,
    /** When the last of those was accepted; null when none was. */
    val lastUsedAt: OffsetDateTime? = null,
    /** The catalog's namespace, which tells two catalogs sharing a key apart (ADR 0034). */
    val namespace: String = "",
    /** The registry the catalog is named on. */
    val registry: String = "",
) {
    /** It serves a draft, live, rather than a release. */
    val isDraft: Boolean get() = version == null
}

/** Reads `environment_catalog_deployments` as [CatalogDeployment]s, releases and drafts alike. */
object DeploymentRows {
    /** The columns and joins of a deployment row, over alias `d`. */
    const val FROM = """
        SELECT d.environment_key, cat.catalog_key AS catalog_key, cat.namespace AS namespace, cat.registry AS registry, d.version,
               COALESCE(dv.version_key, r.version_key) AS version_id,
               CASE WHEN dv.version_id IS NULL THEN NULL WHEN dv.is_next THEN '${CatalogDraft.NEXT_LABEL}' ELSE dv.name END AS draft_name,
               d.deployed_at, d.deployed_by, d.documents_requested, d.last_used_at
        FROM environment_catalog_deployments d
        JOIN catalogs cat ON cat.tenant_key = d.tenant_key AND cat.catalog_id = d.catalog_id
        LEFT JOIN catalog_releases r ON r.tenant_key = d.tenant_key AND r.catalog_id = d.catalog_id AND r.version = d.version
        LEFT JOIN catalog_versions dv ON dv.tenant_key = d.tenant_key AND dv.version_id = d.draft_id
    """

    /** The deployment of release [version], or of draft [draftId], in [environmentKey]; null when it serves neither. */
    fun find(handle: Handle, environmentKey: EnvironmentKey, catalog: CatalogId, version: String? = null, draftId: UUID? = null): CatalogDeployment? = handle.createQuery(
        """
        $FROM
        WHERE d.tenant_key = :t AND d.environment_key = :e AND cat.catalog_id = epistola_catalog_id(:t, :c_ns, :c)
          AND ${if (version != null) "d.version = :v" else "d.draft_id = :d"}
        """,
    )
        .bind("t", catalog.tenantKey)
        .bind("e", environmentKey)
        .bindCatalog("c", catalog)
        .also { q -> if (version != null) q.bind("v", version) else q.bind("d", draftId) }
        .mapTo<CatalogDeployment>()
        .findOne()
        .orElse(null)
}

/** A release cannot be deployed: it does not exist. */
class ReleaseNotDeployableException(
    val catalogKey: CatalogKey,
    val version: String,
    reason: String,
) : RuntimeException("Release ${catalogKey.value}@$version cannot be deployed: $reason")

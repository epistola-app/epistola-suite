// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.queries.releaseNumberOrder
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * The releases of a catalog with content hash [contentHash] (ADR 0034; contract
 * `listCatalogReleases?contentHash=`), highest first: several numbers can name one content (a
 * release re-released with only new notes, one received under another number). Filtered, ordered
 * and paged in the database (`idx_catalog_versions_release_content_hash`). A draft, which has none, is no
 * match.
 *
 * [limit] null returns every match; [offset] skips that many.
 */
data class ListReleasesByContentHash(
    val catalog: CatalogId,
    val contentHash: String,
    val limit: Int? = null,
    val offset: Int = 0,
) : Query<CatalogReleasePage>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.CATALOG_VIEW
}

/** One page of releases, and [total], the number of all matches (ignoring paging). */
data class CatalogReleasePage(val items: List<CatalogRelease>, val total: Int)

@Component
class ListReleasesByContentHashHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListReleasesByContentHash, CatalogReleasePage> {

    private data class Row(val release: CatalogRelease, val total: Int)

    override fun handle(query: ListReleasesByContentHash): CatalogReleasePage = jdbi.withHandle<CatalogReleasePage, Exception> { handle ->
        val rows = handle.createQuery(
            """
            SELECT r.version, r.version_key, r.released_at, r.notes, r.fingerprint, r.content_hash, r.base_version,
                   ARRAY(SELECT d.environment_key::text FROM environment_catalog_deployments d
                         JOIN catalog_versions dv ON dv.tenant_key = d.tenant_key AND dv.version_id = d.target_version_id
                         WHERE d.tenant_key = r.tenant_key AND d.catalog_id = r.catalog_id AND dv.version = r.version
                         ORDER BY d.environment_key) AS deployed_to,
                   -- All matches before LIMIT/OFFSET, from the same statement as the page.
                   COUNT(*) OVER ()::int AS total
            FROM catalog_versions r
            WHERE r.state = 'released' AND r.tenant_key = :t AND r.catalog_id = ${catalogAt("t", "c")} AND r.content_hash = :h
            ORDER BY ${releaseNumberOrder("r")}
            ${if (query.limit != null) "LIMIT :limit" else ""} OFFSET :offset
            """,
        )
            .bind("t", query.tenantKey)
            .bindCatalog("c", query.catalog)
            .bind("h", query.contentHash)
            .bind("offset", query.offset.coerceAtLeast(0))
            .also { statement -> query.limit?.let { statement.bind("limit", it.coerceAtLeast(0)) } }
            .map { rs, _ ->
                Row(
                    CatalogRelease(
                        version = rs.getString("version"),
                        status = CatalogReleaseStatus.RELEASED,
                        versionId = rs.getString("version_key"),
                        base = rs.getString("base_version"),
                        releasedAt = rs.getObject("released_at", OffsetDateTime::class.java),
                        notes = rs.getString("notes"),
                        fingerprint = rs.getString("fingerprint"),
                        contentHash = rs.getString("content_hash"),
                        deployedTo = (rs.getArray("deployed_to").array as Array<*>).map { it as String },
                    ),
                    rs.getInt("total"),
                )
            }
            .list()
        // A page past the end has no row to carry the count; it is empty either way.
        CatalogReleasePage(items = rows.map { it.release }, total = rows.firstOrNull()?.total ?: 0)
    }
}

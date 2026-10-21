// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.catalogAt
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
 * The release history of a catalog, released here or received, newest first.
 *
 * Every release holds the content it was cut with, so an earlier one can be handed over, rendered
 * or deployed exactly as it was released.
 */
data class ListCatalogReleases(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    /** Releases to skip, newest first; with [limit], pages the history in the database. */
    val offset: Int = 0,
    /** At most this many releases; null for all of them. */
    val limit: Int? = null,
) : Query<List<CatalogReleaseSummary>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

data class CatalogReleaseSummary(
    val version: String,
    val releasedAt: OffsetDateTime,
    val notes: String?,
    val fingerprint: String,
    /** How many resources it contained. */
    val resourceCount: Int,
) {
    /** Enough of the fingerprint to compare two releases by eye, as git does with a short hash. */
    val shortFingerprint: String get() = fingerprint.take(12)
}

@Component
class ListCatalogReleasesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListCatalogReleases, List<CatalogReleaseSummary>> {

    override fun handle(query: ListCatalogReleases): List<CatalogReleaseSummary> = jdbi.withHandle<List<CatalogReleaseSummary>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT r.version, r.released_at, r.notes, r.fingerprint,
                   (SELECT count(*) FROM release_entries e
                     WHERE e.tenant_key = r.tenant_key AND e.catalog_id = r.catalog_id AND e.version = r.version
                   ) AS resource_count
            FROM catalog_releases r
            WHERE r.tenant_key = :t AND r.catalog_id = ${catalogAt("t", "c")}
            $LATEST_RELEASE_ORDER
            LIMIT :limit OFFSET :offset
            """,
        )
            .bind("t", query.tenantKey)
            .bind("c", query.catalogKey)
            .bind("limit", query.limit)
            .bind("offset", query.offset)
            .map { rs, _ ->
                CatalogReleaseSummary(
                    version = rs.getString("version"),
                    releasedAt = rs.getObject("released_at", OffsetDateTime::class.java),
                    notes = rs.getString("notes"),
                    fingerprint = rs.getString("fingerprint"),
                    resourceCount = rs.getInt("resource_count"),
                )
            }
            .list()
    }
}

/** How many releases a catalog has, to page [ListCatalogReleases]. */
data class CountCatalogReleases(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<Long>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class CountCatalogReleasesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<CountCatalogReleases, Long> {
    override fun handle(query: CountCatalogReleases): Long = jdbi.withHandle<Long, Exception> { handle ->
        handle.createQuery("SELECT count(*) FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
            .bind("t", query.tenantKey)
            .bind("c", query.catalogKey)
            .mapTo(Long::class.java)
            .one()
    }
}

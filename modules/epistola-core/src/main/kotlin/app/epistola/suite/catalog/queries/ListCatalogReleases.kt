// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

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
 * The release history of an AUTHORED catalog, newest first.
 *
 * A release used to be a row nothing rendered — the version pointer was on the catalog and the
 * history behind it was invisible. It is worth showing now because a release is no longer only a
 * version and a fingerprint: it retains the content it contained, so an earlier one can be handed
 * over exactly as it was released.
 *
 * Which is also why [CatalogReleaseSummary.retained] is on every row rather than assumed. Releases
 * cut before `V20260923201010` kept no content and cannot be rebuilt, so a screen that offered them
 * would be offering something that fails at download.
 */
data class ListCatalogReleases(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<List<CatalogReleaseSummary>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

data class CatalogReleaseSummary(
    val version: String,
    val releasedAt: OffsetDateTime,
    val notes: String?,
    val fingerprint: String,
    /** Whether this release kept its content, and so can be reproduced and exported as released. */
    val retained: Boolean,
    /** How many resources it contained; null when it retained nothing to count. */
    val resourceCount: Int?,
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
            SELECT r.version, r.released_at, r.notes, r.fingerprint, r.content_retained,
                   (SELECT count(*) FROM release_entries e
                     WHERE e.tenant_key = r.tenant_key AND e.catalog_key = r.catalog_key AND e.version = r.version
                   ) AS resource_count
            FROM catalog_releases r
            WHERE r.tenant_key = :t AND r.catalog_key = :c
            $LATEST_RELEASE_ORDER
            """,
        )
            .bind("t", query.tenantKey)
            .bind("c", query.catalogKey)
            .map { rs, _ ->
                val retained = rs.getBoolean("content_retained")
                val count = rs.getInt("resource_count")
                CatalogReleaseSummary(
                    version = rs.getString("version"),
                    releasedAt = rs.getObject("released_at", OffsetDateTime::class.java),
                    notes = rs.getString("notes"),
                    fingerprint = rs.getString("fingerprint"),
                    // The release says whether it kept its content. Counting entries cannot:
                    // a catalog with no resources retains a release that contains nothing.
                    retained = retained,
                    resourceCount = count.takeIf { retained },
                )
            }
            .list()
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.suite.catalog.revisions.ReleaseEntry
import app.epistola.suite.catalog.revisions.ReleaseEntryStore
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * One release as it was: its resources and the catalog details it carried.
 *
 * Everything here comes from what the release froze — `release_entries` for the resources,
 * the manifest snapshot for the catalog's own name, keywords, presentation and license — and
 * nothing from the live catalog row. That is the point of the page it backs: a catalog's details
 * are version-dependent, and the working copy has been showing them as though they were not.
 */
data class GetCatalogRelease(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
) : Query<CatalogReleaseDetail?>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

data class CatalogReleaseDetail(
    val version: String,
    val releasedAt: OffsetDateTime,
    val notes: String?,
    val fingerprint: String,
    /** The catalog's own details as this release carried them, not as the working copy has them now. */
    val catalog: CatalogInfo,
    /**
     * What the release contained, or empty when it kept no content.
     *
     * The distinction matters on the page: a release that kept nothing can still show its details,
     * because the manifest snapshot has always been stored, but it cannot list its resources and
     * must not pretend the catalog was empty.
     */
    val resources: List<ReleaseEntry>,
    val retained: Boolean,
) {
    val shortFingerprint: String get() = fingerprint.take(12)
}

@Component
class GetCatalogReleaseHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val releaseEntryStore: ReleaseEntryStore,
) : QueryHandler<GetCatalogRelease, CatalogReleaseDetail?> {

    override fun handle(query: GetCatalogRelease): CatalogReleaseDetail? = jdbi.withHandle<CatalogReleaseDetail?, Exception> { handle ->
        val row = handle.createQuery(
            """
            SELECT version, released_at, notes, fingerprint, content_retained, manifest_snapshot::text AS snapshot
            FROM catalog_releases
            WHERE tenant_key = :t AND catalog_key = :c AND version = :version
            """,
        )
            .bind("t", query.tenantKey)
            .bind("c", query.catalogKey)
            .bind("version", query.version)
            .map { rs, _ ->
                CatalogReleaseDetail(
                    version = rs.getString("version"),
                    releasedAt = rs.getObject("released_at", OffsetDateTime::class.java),
                    notes = rs.getString("notes"),
                    fingerprint = rs.getString("fingerprint"),
                    catalog = objectMapper.readValue(rs.getString("snapshot"), CatalogManifest::class.java).catalog,
                    resources = emptyList(),
                    retained = rs.getBoolean("content_retained"),
                )
            }
            .findOne()
            .orElse(null)
            ?: return@withHandle null

        row.copy(resources = releaseEntryStore.entriesOf(handle, query.tenantKey, query.catalogKey, query.version))
    }
}

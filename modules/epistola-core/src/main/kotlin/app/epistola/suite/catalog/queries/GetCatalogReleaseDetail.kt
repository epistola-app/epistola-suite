// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.suite.catalog.CatalogCanonicalizer
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.revisions.ReleaseResource
import app.epistola.suite.catalog.revisions.ReleaseResourceStore
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.installation.InstallationService
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.UUID

/**
 * One release as it was: its resources and the catalog details it carried.
 *
 * Everything here comes from what the release froze — `release_resources` for the resources,
 * the manifest snapshot for the catalog's own name, keywords, presentation and license — and
 * nothing from the live catalog row. That is the point of the page it backs: a catalog's details
 * are version-dependent, and the working copy has been showing them as though they were not.
 */
data class GetCatalogReleaseDetail(
    val catalog: CatalogId,
    val version: String,
) : Query<CatalogReleaseDetail?>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.CATALOG_VIEW
}

data class CatalogReleaseDetail(
    val version: String,
    val releasedAt: OffsetDateTime,
    val notes: String?,
    val fingerprint: String,
    /** The catalog's own details as this release carried them, not as the working copy has them now. */
    val catalog: CatalogInfo,
    /** What the release contained. */
    val resources: List<ReleaseResource>,
    /** The installation that cut it; null when nothing says (a release recorded before 2.0 said it). */
    val releasedByInstallation: UUID? = null,
    /** Whether [releasedByInstallation] is this installation: the release was cut here. */
    val releasedHere: Boolean = false,
    /** The display name of the user who released it here; null for a received release or when unknown. */
    val releasedByUser: String? = null,
    /**
     * What identifies it (ADR 0034): the V6 fingerprint of its content alone. Null while it is being
     * computed, for a release recorded before content hashes that the backfill has not reached.
     */
    val contentHash: String? = null,
) {
    val shortFingerprint: String get() = fingerprint.take(12)

    /** [contentHash] as short as [shortFingerprint]; null while it is being computed. */
    val shortContentHash: String? get() = contentHash?.take(12)

    /**
     * It was recorded with a fingerprint of an older algorithm than the content hash (V5 or a bare
     * one), before releases were identified by their content: the page shows that fingerprint
     * beside the content hash the release has been given, and says why there are two.
     */
    val recordedBeforeContentHashes: Boolean get() = !CatalogCanonicalizer.isContentHash(fingerprint)

    /**
     * Who released it, in one sentence for the release page: "Released here by Ann", "Released here"
     * when the user is not known, "Released by installation <id>" for a received release; null when
     * nothing says.
     */
    val releasedByLine: String? get() = when {
        releasedHere -> releasedByUser?.let { "Released here by $it" } ?: "Released here"
        releasedByInstallation != null -> "Released by installation $releasedByInstallation"
        else -> null
    }
}

@Component
class GetCatalogReleaseDetailHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val releaseResourceStore: ReleaseResourceStore,
    private val installation: InstallationService,
) : QueryHandler<GetCatalogReleaseDetail, CatalogReleaseDetail?> {

    override fun handle(query: GetCatalogReleaseDetail): CatalogReleaseDetail? = jdbi.withHandle<CatalogReleaseDetail?, Exception> { handle ->
        val row = handle.createQuery(
            """
            SELECT r.version, r.released_at, r.notes, r.fingerprint, r.content_hash, r.manifest_snapshot::text AS snapshot,
                   r.released_by_installation, u.display_name AS released_by_name
            FROM catalog_releases r
            LEFT JOIN users u ON u.id = r.released_by
            WHERE r.tenant_key = :t AND r.catalog_id = ${catalogAt("t", "c")} AND r.version = :version
            """,
        )
            .bind("t", query.tenantKey)
            .bindCatalog("c", query.catalog)
            .bind("version", query.version)
            .map { rs, _ ->
                CatalogReleaseDetail(
                    version = rs.getString("version"),
                    releasedAt = rs.getObject("released_at", OffsetDateTime::class.java),
                    notes = rs.getString("notes"),
                    fingerprint = rs.getString("fingerprint"),
                    catalog = objectMapper.readValue(rs.getString("snapshot"), CatalogManifest::class.java).catalog,
                    resources = emptyList(),
                    releasedByInstallation = rs.getObject("released_by_installation", UUID::class.java),
                    releasedByUser = rs.getString("released_by_name"),
                    contentHash = rs.getString("content_hash"),
                )
            }
            .findOne()
            .orElse(null)
            ?: return@withHandle null

        row.copy(
            resources = releaseResourceStore.entriesOf(handle, query.catalog, query.version),
            releasedHere = row.releasedByInstallation != null && row.releasedByInstallation == installation.get().id,
        )
    }
}

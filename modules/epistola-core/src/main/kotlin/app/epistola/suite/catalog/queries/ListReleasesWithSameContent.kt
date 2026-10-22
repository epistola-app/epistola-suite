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
import java.util.UUID

/**
 * The other releases in the tenant that are the same release as release [version] of [catalogKey]
 * (ADR 0034): in any catalog, under any number, with the same content hash. A release re-released
 * with only new notes shares its content hash with the one before it, and so does the same release
 * received by another route, or the same content released in another catalog.
 *
 * It reads what is recorded and computes nothing. A release whose content hash is not computed yet
 * ([ReleaseContentHashBackfill][app.epistola.suite.catalog.revisions.ReleaseContentHashBackfill] has
 * not reached it) answers [ReleasesWithSameContent.pending] and lists none, and another release
 * without one yet is not listed. Null when the catalog has no such release.
 */
data class ListReleasesWithSameContent(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
) : Query<ReleasesWithSameContent?>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/**
 * What [ListReleasesWithSameContent] found: the release's [contentHash], null while it is being
 * computed, and the [releases] with the same one, those of the same catalog first (highest number
 * first), then the other catalogs' by name.
 */
data class ReleasesWithSameContent(
    val contentHash: String?,
    val releases: List<SameContentRelease>,
) {
    /** The content hash is not computed yet, so nothing can be said about which releases share it. */
    val pending: Boolean get() = contentHash == null
}

/** A release with the same content: its catalog by key and name, its number, and whether it is the asked catalog's own. */
data class SameContentRelease(
    val catalogKey: String,
    val catalogName: String,
    val version: String,
    val sameCatalog: Boolean,
)

@Component
class ListReleasesWithSameContentHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListReleasesWithSameContent, ReleasesWithSameContent?> {

    override fun handle(query: ListReleasesWithSameContent): ReleasesWithSameContent? = jdbi.withHandle<ReleasesWithSameContent?, Exception> { handle ->
        val own = handle.createQuery(
            "SELECT catalog_id, content_hash FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :v",
        )
            .bind("t", query.tenantKey)
            .bind("c", query.catalogKey)
            .bind("v", query.version)
            .map { rs, _ -> rs.getObject("catalog_id", UUID::class.java) to rs.getString("content_hash") }
            .findOne()
            .orElse(null)
            ?: return@withHandle null
        val (catalogId, contentHash) = own
        if (contentHash == null) return@withHandle ReleasesWithSameContent(contentHash = null, releases = emptyList())

        val releases = handle.createQuery(
            """
            SELECT c.catalog_key, c.name, r.version, (r.catalog_id = :own) AS same_catalog
            FROM catalog_releases r
            JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
            WHERE r.tenant_key = :t AND r.content_hash = :h AND NOT (r.catalog_id = :own AND r.version = :v)
            ORDER BY same_catalog DESC, c.name, c.catalog_key, ${releaseNumberOrder("r")}
            """,
        )
            .bind("t", query.tenantKey)
            .bind("h", contentHash)
            .bind("own", catalogId)
            .bind("v", query.version)
            .map { rs, _ -> SameContentRelease(rs.getString("catalog_key"), rs.getString("name"), rs.getString("version"), rs.getBoolean("same_catalog")) }
            .list()
        ReleasesWithSameContent(contentHash, releases)
    }
}

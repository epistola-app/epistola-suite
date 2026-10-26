// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.CatalogId
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
 * It reads what is recorded and computes nothing: every release records its content hash. Null when
 * the catalog has no such release.
 */
data class ListReleasesWithSameContent(
    val catalog: CatalogId,
    val version: String,
) : Query<ReleasesWithSameContent?>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.CATALOG_VIEW
}

/**
 * What [ListReleasesWithSameContent] found: the release's [contentHash] and the [releases] with the
 * same one, those of the same catalog first (highest number first), then the other catalogs' by name.
 */
data class ReleasesWithSameContent(
    val contentHash: String,
    val releases: List<SameContentRelease>,
)

/** A release with the same content: its catalog by key and name, its number, and whether it is the asked catalog's own. */
data class SameContentRelease(
    val catalogKey: String,
    val catalogName: String,
    val version: String,
    val sameCatalog: Boolean,
    /** Its catalog's namespace: with [catalogKey], the address its pages are at (ADR 0034, section 9). */
    val namespace: String = "",
) {
    /** `namespace/key`, as its pages are addressed. */
    val address: String get() = "$namespace/$catalogKey"
}

@Component
class ListReleasesWithSameContentHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListReleasesWithSameContent, ReleasesWithSameContent?> {

    override fun handle(query: ListReleasesWithSameContent): ReleasesWithSameContent? = jdbi.withHandle<ReleasesWithSameContent?, Exception> { handle ->
        val own = handle.createQuery(
            "SELECT catalog_id, content_hash FROM catalog_versions WHERE state = 'released' AND tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :v",
        )
            .bind("t", query.tenantKey)
            .bindCatalog("c", query.catalog)
            .bind("v", query.version)
            .map { rs, _ -> rs.getObject("catalog_id", UUID::class.java) to rs.getString("content_hash") }
            .findOne()
            .orElse(null)
            ?: return@withHandle null
        val (catalogId, contentHash) = own

        val releases = handle.createQuery(
            """
            SELECT c.catalog_key, c.namespace, c.name, r.version, (r.catalog_id = :own) AS same_catalog
            FROM catalog_versions r
            JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
            WHERE r.state = 'released' AND r.tenant_key = :t AND r.content_hash = :h AND NOT (r.catalog_id = :own AND r.version = :v)
            ORDER BY same_catalog DESC, c.name, c.catalog_key, ${releaseNumberOrder("r")}
            """,
        )
            .bind("t", query.tenantKey)
            .bind("h", contentHash)
            .bind("own", catalogId)
            .bind("v", query.version)
            .map { rs, _ -> SameContentRelease(rs.getString("catalog_key"), rs.getString("name"), rs.getString("version"), rs.getBoolean("same_catalog"), rs.getString("namespace")) }
            .list()
        ReleasesWithSameContent(contentHash, releases)
    }
}

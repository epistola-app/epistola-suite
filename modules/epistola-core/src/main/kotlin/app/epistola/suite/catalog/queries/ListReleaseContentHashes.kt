// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogCanonicalizer
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * The content hash (ADR 0034, `catalog_releases.content_hash`) of every release of the tenant that has
 * one, by catalog and number, in one read: for naming the releases a page lists (a catalog's highest
 * release, a dependency's pinned one) by their content without a query each. A release without one
 * yet (recorded before content hashes, until the backfill reaches it) is not in it.
 */
data class ListReleaseContentHashes(
    override val tenantKey: TenantKey,
) : Query<ReleaseContentHashes>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/** Content hashes by catalog key and release number. */
data class ReleaseContentHashes(val byRelease: Map<Pair<CatalogKey, String>, String>) {
    /** The content hash of release [version] of [catalogKey], or null when it has none (or there is no such release). */
    fun of(catalogKey: CatalogKey, version: String?): String? = version?.let { byRelease[catalogKey to it] }

    /**
     * The content hash of the release [dependency] pins: its pin when that is a content hash, else the
     * release's here. Null for a declaration that pins none, or a release without one yet.
     */
    fun pinnedBy(dependency: CatalogDependencyDeclaration): String? = dependency.releaseFingerprint?.takeIf(CatalogCanonicalizer::isContentHash)
        ?: of(CatalogKey.of(dependency.catalogKey), dependency.releaseVersion)
}

@Component
class ListReleaseContentHashesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListReleaseContentHashes, ReleaseContentHashes> {
    override fun handle(query: ListReleaseContentHashes): ReleaseContentHashes = jdbi.withHandle<ReleaseContentHashes, Exception> { handle ->
        ReleaseContentHashes(
            handle.createQuery(
                """
                SELECT c.catalog_key, r.version, r.content_hash FROM catalog_releases r
                JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
                WHERE r.tenant_key = :t AND r.content_hash IS NOT NULL
                """,
            )
                .bind("t", query.tenantKey)
                .map { rs, _ -> (CatalogKey.of(rs.getString(1)) to rs.getString(2)) to rs.getString(3) }
                .list()
                .toMap(),
        )
    }
}

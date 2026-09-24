// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Which catalog a resource belongs to, and where that catalog stands.
 *
 * Editing a template means editing the working copy of its catalog, and until now a template page
 * did not say which catalog that was, let alone what had been released from it. Someone could edit
 * for an hour without knowing whether their work was in a released version or waiting to be.
 *
 * Deliberately not a release: a working copy is not a version. What this reports is the version the
 * catalog was last released at, and whether anything has moved since — which together say what
 * releasing now would do.
 */
data class GetCatalogContext(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<CatalogContext?>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

data class CatalogContext(
    val catalogKey: String,
    val name: String,
    val type: CatalogType,
    /** The version last released from this catalog, or null when it never has been. */
    val releasedVersion: String?,
    /** Something has been edited since that release. Always false for a catalog nobody authors. */
    val pendingChanges: Boolean,
) {
    val authored: Boolean get() = type == CatalogType.AUTHORED

    /** There is a release to cut: an authored catalog with either changes or no release at all. */
    val releasable: Boolean get() = authored && (pendingChanges || releasedVersion == null)
}

@Component
class GetCatalogContextHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetCatalogContext, CatalogContext?> {

    override fun handle(query: GetCatalogContext): CatalogContext? = jdbi.withHandle<CatalogContext?, Exception> { handle ->
        handle.createQuery(
            """
            $CATALOG_ACTIVITY_CTE
            SELECT c.id, c.name, c.type, c.released_version, $CATALOG_PENDING_CHANGES
            FROM catalogs c
            $CATALOG_ACTIVITY_JOIN
            WHERE c.tenant_key = :t AND c.id = :c
            """,
        )
            .bind("t", query.tenantKey)
            .bind("c", query.catalogKey)
            .map { rs, _ ->
                CatalogContext(
                    catalogKey = rs.getString("id"),
                    name = rs.getString("name"),
                    type = CatalogType.valueOf(rs.getString("type")),
                    releasedVersion = rs.getString("released_version"),
                    pendingChanges = rs.getBoolean("pending_changes"),
                )
            }
            .findOne()
            .orElse(null)
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.queries

import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Total number of stencils matching the same filters as [ListStencils], ignoring pagination: the
 * `total` of a paginated list.
 */
data class CountStencils(
    val tenantId: TenantId,
    val searchTerm: String? = null,
    val tag: String? = null,
    val catalogKey: CatalogKey? = null,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Query<Long>,
    RequiresPermission {
    override val permission = Permission.STENCIL_VIEW
    override val tenantKey: TenantKey get() = tenantId.key
}

@Component
class CountStencilsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<CountStencils, Long> {
    override fun handle(query: CountStencils): Long = jdbi.withHandle<Long, Exception> { handle ->
        val sql = buildString {
            append("SELECT COUNT(*) FROM stencils s WHERE s.tenant_key = :tenantId AND ${inCurrentVersion("s")}")
            if (query.catalogKey != null) {
                append(" AND s.version_id = ${versionAt("tenantId", "catalogKey")}")
            }
            if (!query.searchTerm.isNullOrBlank()) {
                append(" AND (s.name ILIKE :searchTerm OR s.description ILIKE :searchTerm)")
            }
            if (!query.tag.isNullOrBlank()) {
                append(" AND s.tags @> :tag::jsonb")
            }
        }
        val jdbiQuery = handle.createQuery(sql).bind("tenantId", query.tenantId.key)
        if (query.catalogKey != null) {
            jdbiQuery.bind("catalogKey", query.catalogKey)
        }
        if (!query.searchTerm.isNullOrBlank()) {
            jdbiQuery.bind("searchTerm", "%${query.searchTerm}%")
        }
        if (!query.tag.isNullOrBlank()) {
            jdbiQuery.bind("tag", "[\"${query.tag}\"]")
        }
        jdbiQuery.mapTo<Long>().one()
    }
}

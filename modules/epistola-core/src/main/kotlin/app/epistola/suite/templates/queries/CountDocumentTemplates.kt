// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.queries

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Total number of templates matching the same filters as [ListDocumentTemplates],
 * ignoring pagination — the `totalElements` for the paginated REST list envelope.
 */
data class CountDocumentTemplates(
    val tenantId: TenantId,
    val catalog: CatalogId? = null,
    val searchTerm: String? = null,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : ReleaseReadable,
    Query<Long>,
    RequiresPermission {
    init {
        validate("catalog", catalog == null || catalog.tenantId == tenantId) { "The catalog is of the tenant" }
    }

    val catalogKey: CatalogKey? get() = catalog?.key
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = tenantId.key
}

@Component
class CountDocumentTemplatesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<CountDocumentTemplates, Long> {
    override fun handle(query: CountDocumentTemplates): Long = jdbi.withHandle<Long, Exception> { handle ->
        val sql = buildString {
            // Each catalog counts once, in its current version.
            append("SELECT COUNT(*) FROM current_templates dt WHERE dt.tenant_key = :tenantId")
            if (query.catalogKey != null) {
                append(" AND dt.catalog_id = ${catalogAt("tenantId", "catalogKey")}")
            }
            if (!query.searchTerm.isNullOrBlank()) {
                append(" AND dt.name ILIKE :searchTerm")
            }
        }
        val jdbiQuery = handle.createQuery(sql).bind("tenantId", query.tenantId.key)
        if (query.catalogKey != null) {
            jdbiQuery.bindCatalog("catalogKey", query.catalog)
        }
        if (!query.searchTerm.isNullOrBlank()) {
            jdbiQuery.bind("searchTerm", "%${query.searchTerm}%")
        }
        jdbiQuery.mapTo<Long>().one()
    }
}

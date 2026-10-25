// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.queries

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.STENCIL_COLUMNS
import app.epistola.suite.stencils.STENCIL_FROM
import app.epistola.suite.stencils.Stencil
import app.epistola.suite.stencils.withAbsentSchemaAsNull
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListStencils(
    val tenantId: TenantId,
    val catalog: CatalogId? = null,
    val searchTerm: String? = null,
    val tag: String? = null,
    val limit: Int = 50,
    val offset: Int = 0,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : ReleaseReadable,
    Query<List<Stencil>>,
    RequiresPermission {
    init {
        validate("catalog", catalog == null || catalog.tenantId == tenantId) { "The catalog is of the tenant" }
    }

    val catalogKey: CatalogKey? get() = catalog?.key
    override val permission = Permission.STENCIL_VIEW
    override val tenantKey: TenantKey get() = tenantId.key
}

@Component
class ListStencilsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListStencils, List<Stencil>> {
    override fun handle(query: ListStencils): List<Stencil> = jdbi.withHandle<List<Stencil>, Exception> { handle ->
        val sql = buildString {
            append("SELECT $STENCIL_COLUMNS $STENCIL_FROM WHERE s.tenant_key = :tenantId")
            if (query.catalogKey != null) {
                append(" AND s.catalog_id = ${catalogAt("tenantId", "catalogKey")}")
            }
            if (!query.searchTerm.isNullOrBlank()) {
                append(" AND (s.name ILIKE :searchTerm OR s.description ILIKE :searchTerm)")
            }
            if (!query.tag.isNullOrBlank()) {
                // Bound as a value, so a tag with a quote is a tag, not broken JSON.
                append(" AND s.tags @> jsonb_build_array(CAST(:tag AS TEXT))")
            }
            append(" ORDER BY s.updated_at DESC")
            append(" LIMIT :limit OFFSET :offset")
        }

        val jdbiQuery = handle.createQuery(sql)
            .bind("tenantId", query.tenantId.key)
        if (query.catalogKey != null) {
            jdbiQuery.bindCatalog("catalogKey", query.catalog)
        }
        if (!query.searchTerm.isNullOrBlank()) {
            jdbiQuery.bind("searchTerm", "%${query.searchTerm}%")
        }
        if (!query.tag.isNullOrBlank()) {
            jdbiQuery.bind("tag", query.tag)
        }
        jdbiQuery
            .bind("limit", query.limit)
            .bind("offset", query.offset)
            .mapTo<Stencil>()
            .map(Stencil::withAbsentSchemaAsNull)
            .list()
    }
}

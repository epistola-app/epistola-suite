// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes.queries

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.themes.THEMES_WITH_CATALOG
import app.epistola.suite.themes.THEME_COLUMNS
import app.epistola.suite.themes.Theme
import app.epistola.suite.themes.ThemeRows
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class ListThemes(
    val tenantId: TenantId,
    val catalog: CatalogId? = null,
    val searchTerm: String? = null,
    /** The catalog version this addresses: a draft or a release (ADR 0033); null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : ReleaseReadable,
    Query<List<Theme>>,
    RequiresPermission {
    init {
        validate("catalog", catalog == null || catalog.tenantId == tenantId) { "The catalog is of the tenant" }
    }

    val catalogKey: CatalogKey? get() = catalog?.key
    override val permission get() = Permission.THEME_VIEW
    override val tenantKey get() = tenantId.key
}

@Component
class ListThemesHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<ListThemes, List<Theme>> {
    override fun handle(query: ListThemes): List<Theme> = jdbi.withHandle<List<Theme>, Exception> { handle ->
        val sql = buildString {
            append("SELECT $THEME_COLUMNS FROM $THEMES_WITH_CATALOG WHERE t.tenant_key = :tenantId")
            if (query.catalogKey != null) {
                append(" AND t.catalog_id = ${catalogAt("tenantId", "catalogKey")}")
            }
            if (!query.searchTerm.isNullOrBlank()) {
                append(" AND t.name ILIKE :searchTerm")
            }
            append(" ORDER BY t.updated_at DESC, t.id")
        }

        val jdbiQuery = handle.createQuery(sql)
            .bind("tenantId", query.tenantId.key)
        if (query.catalogKey != null) {
            jdbiQuery.bindCatalog("catalogKey", query.catalog)
        }
        if (!query.searchTerm.isNullOrBlank()) {
            jdbiQuery.bind("searchTerm", "%${query.searchTerm}%")
        }
        jdbiQuery
            .map(ThemeRows(objectMapper))
            .list()
    }
}

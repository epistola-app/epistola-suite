// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes.queries

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.common.PageRequest
import app.epistola.suite.common.Paging
import app.epistola.suite.common.ResultPage
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
import org.jdbi.v3.core.Handle
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

/** [ListThemes], one page of it, cut in the database. */
data class ListThemePage(
    val tenantId: TenantId,
    val catalog: CatalogId? = null,
    val searchTerm: String? = null,
    /** The catalog version this addresses: a draft or a release (ADR 0033); null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
    val page: PageRequest,
) : ReleaseReadable,
    Query<ResultPage<Theme>>,
    RequiresPermission {
    init {
        validate("catalog", catalog == null || catalog.tenantId == tenantId) { "The catalog is of the tenant" }
    }
    override val permission get() = Permission.THEME_VIEW
    override val tenantKey get() = tenantId.key
}

@Component
class ListThemesHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<ListThemes, List<Theme>> {
    override fun handle(query: ListThemes): List<Theme> = jdbi.withHandle<List<Theme>, Exception> { handle ->
        ThemeList.query(handle, query.tenantId, query.catalog, query.searchTerm, paged = false)
            .map(ThemeList.rows(handle, objectMapper, query.tenantId, query.catalog))
            .list()
    }
}

@Component
class ListThemePageHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<ListThemePage, ResultPage<Theme>> {
    override fun handle(query: ListThemePage): ResultPage<Theme> = jdbi.withHandle<ResultPage<Theme>, Exception> { handle ->
        Paging.read(query.page, ThemeList.rows(handle, objectMapper, query.tenantId, query.catalog)) {
            ThemeList.query(handle, query.tenantId, query.catalog, query.searchTerm, paged = true)
        }
    }
}

/** The themes of a tenant, or of one of its catalogs, last changed first; [paged] selects one page of them. */
private object ThemeList {
    fun query(handle: Handle, tenantId: TenantId, catalog: CatalogId?, searchTerm: String?, paged: Boolean): org.jdbi.v3.core.statement.Query {
        val sql = buildString {
            append("SELECT $THEME_COLUMNS")
            if (paged) append(", ${Paging.TOTAL}")
            append(" FROM $THEMES_WITH_CATALOG WHERE t.tenant_key = :tenantId")
            if (catalog != null) append(" AND t.catalog_id = ${catalogAt("tenantId", "catalogKey")}")
            if (!searchTerm.isNullOrBlank()) append(" AND t.name ILIKE :searchTerm")
            append(" ORDER BY t.updated_at DESC, t.catalog_id, t.id")
            if (paged) append(" ${Paging.LIMIT_OFFSET}")
        }
        val q = handle.createQuery(sql).bind("tenantId", tenantId.key)
        if (catalog != null) q.bindCatalog("catalogKey", catalog)
        if (!searchTerm.isNullOrBlank()) q.bind("searchTerm", "%$searchTerm%")
        return q
    }

    fun rows(handle: Handle, objectMapper: ObjectMapper, tenantId: TenantId, catalog: CatalogId?): ThemeRows = catalog?.let { ThemeRows.of(objectMapper, it) } ?: ThemeRows.ofTenant(handle, objectMapper, tenantId.key)
}

package app.epistola.suite.themes.queries

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.paging.PageRequest
import app.epistola.suite.common.paging.PagedResult
import app.epistola.suite.common.paging.SortDirection
import app.epistola.suite.common.paging.SortSpec
import app.epistola.suite.common.paging.SortWhitelist
import app.epistola.suite.common.paging.pagedQuery
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.themes.Theme
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.mapper.Nested
import org.springframework.stereotype.Component

/**
 * Allowed sort columns: logical key → fixed SQL expression. Anything outside this
 * whitelist falls back to the default rather than reaching `ORDER BY`. See ADR 0007.
 */
private val THEME_SORT = SortWhitelist(
    columns = mapOf(
        "name" to "t.name",
        "updated" to "t.updated_at",
    ),
    default = SortSpec("updated", SortDirection.DESC),
)

data class ListThemes(
    val tenantId: TenantId,
    val searchTerm: String? = null,
    val catalogKey: CatalogKey? = null,
    val sort: SortSpec = THEME_SORT.default,
    val page: PageRequest = PageRequest(page = 1, size = 50),
) : Query<PagedResult<Theme>>,
    RequiresPermission {
    override val permission get() = Permission.THEME_VIEW
    override val tenantKey get() = tenantId.key
}

/**
 * Row shape: the full [Theme] (mapped from `t.*` + `catalog_type` by its existing
 * KotlinMapper via @Nested, so the JSON columns keep working) plus the windowed total.
 */
private data class ThemeRow(
    @Nested val theme: Theme,
    val totalCount: Long,
)

@Component
class ListThemesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListThemes, PagedResult<Theme>> {
    override fun handle(query: ListThemes): PagedResult<Theme> = jdbi.withHandle<PagedResult<Theme>, Exception> { handle ->
        val sql = buildString {
            append(
                "SELECT t.*, c.type AS catalog_type, COUNT(*) OVER() AS total_count " +
                    "FROM themes t JOIN catalogs c ON c.tenant_key = t.tenant_key AND c.id = t.catalog_key " +
                    "WHERE t.tenant_key = :tenantId",
            )
            if (query.catalogKey != null) {
                append(" AND t.catalog_key = :catalogKey")
            }
            if (!query.searchTerm.isNullOrBlank()) {
                append(" AND t.name ILIKE :searchTerm")
            }
            // t.id tiebreaker keeps offset paging deterministic when the sort column ties.
            append(" ORDER BY ${THEME_SORT.orderBy(query.sort, tiebreaker = "t.id")}")
            append(" LIMIT :limit OFFSET :offset")
        }

        handle.pagedQuery<ThemeRow, Theme>(
            sql = sql,
            page = query.page,
            bind = { jdbiQuery ->
                jdbiQuery.bind("tenantId", query.tenantId.key)
                if (query.catalogKey != null) {
                    jdbiQuery.bind("catalogKey", query.catalogKey)
                }
                if (!query.searchTerm.isNullOrBlank()) {
                    jdbiQuery.bind("searchTerm", "%${query.searchTerm}%")
                }
                jdbiQuery
            },
            totalOf = { it.totalCount },
            map = { it.theme },
        )
    }
}

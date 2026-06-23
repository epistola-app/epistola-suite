package app.epistola.suite.stencils.queries

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
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
import app.epistola.suite.stencils.Stencil
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.mapper.Nested
import org.springframework.stereotype.Component

/**
 * Allowed sort columns: logical key → fixed SQL expression. Anything outside this
 * whitelist falls back to the default rather than reaching `ORDER BY`. See ADR 0007.
 */
private val STENCIL_SORT = SortWhitelist(
    columns = mapOf(
        "name" to "s.name",
        "updated" to "s.updated_at",
    ),
    default = SortSpec("updated", SortDirection.DESC),
)

data class ListStencils(
    val tenantId: TenantId,
    val searchTerm: String? = null,
    val tag: String? = null,
    val catalogKey: CatalogKey? = null,
    val sort: SortSpec = STENCIL_SORT.default,
    val page: PageRequest = PageRequest(page = 1, size = 50),
) : Query<PagedResult<Stencil>>,
    RequiresPermission {
    override val permission = Permission.STENCIL_VIEW
    override val tenantKey: TenantKey get() = tenantId.key
}

/** Row shape: the full [Stencil] (mapped via @Nested, so the @Json tags keep working) + total. */
private data class StencilRow(
    @Nested val stencil: Stencil,
    val totalCount: Long,
)

@Component
class ListStencilsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListStencils, PagedResult<Stencil>> {
    override fun handle(query: ListStencils): PagedResult<Stencil> = jdbi.withHandle<PagedResult<Stencil>, Exception> { handle ->
        val sql = buildString {
            append(
                "SELECT s.id, s.tenant_key, s.catalog_key, c.type AS catalog_type, s.name, s.description, s.tags, " +
                    "s.created_at, s.updated_at, s.created_by, s.updated_by, COUNT(*) OVER() AS total_count " +
                    "FROM stencils s JOIN catalogs c ON c.tenant_key = s.tenant_key AND c.id = s.catalog_key " +
                    "WHERE s.tenant_key = :tenantId",
            )
            if (query.catalogKey != null) {
                append(" AND s.catalog_key = :catalogKey")
            }
            if (!query.searchTerm.isNullOrBlank()) {
                append(" AND (s.name ILIKE :searchTerm OR s.description ILIKE :searchTerm)")
            }
            if (!query.tag.isNullOrBlank()) {
                append(" AND s.tags @> :tag::jsonb")
            }
            // s.id tiebreaker keeps offset paging deterministic when the sort column ties.
            append(" ORDER BY ${STENCIL_SORT.orderBy(query.sort, tiebreaker = "s.id")}")
            append(" LIMIT :limit OFFSET :offset")
        }

        handle.pagedQuery<StencilRow, Stencil>(
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
                if (!query.tag.isNullOrBlank()) {
                    jdbiQuery.bind("tag", "[\"${query.tag}\"]")
                }
                jdbiQuery
            },
            totalOf = { it.totalCount },
            map = { it.stencil },
        )
    }
}

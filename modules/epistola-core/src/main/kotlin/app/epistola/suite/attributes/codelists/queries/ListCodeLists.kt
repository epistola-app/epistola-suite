package app.epistola.suite.attributes.codelists.queries

import app.epistola.suite.attributes.codelists.model.CodeList
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.paging.PageRequest
import app.epistola.suite.common.paging.PagedResult
import app.epistola.suite.common.paging.SortDirection
import app.epistola.suite.common.paging.SortSpec
import app.epistola.suite.common.paging.SortWhitelist
import app.epistola.suite.common.paging.ilikeContains
import app.epistola.suite.common.paging.pagedQuery
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.mapper.Nested
import org.springframework.stereotype.Component

/**
 * Allowed sort columns: logical key → fixed SQL expression. Anything outside this
 * whitelist falls back to the default rather than reaching `ORDER BY`. See ADR 0007.
 */
private val CODE_LIST_SORT = SortWhitelist(
    columns = mapOf(
        "name" to "cl.display_name",
        "slug" to "cl.slug",
        "created" to "cl.created_at",
    ),
    default = SortSpec("name", SortDirection.ASC),
)

/**
 * Lists code lists owned by a tenant, optionally filtered to a single catalog.
 *
 * Each row carries the owning catalog's `type` so the UI can gate edit
 * affordances (AUTHORED is editable, SUBSCRIBED is read-only). Entries are
 * not fetched here; use `ListCodeListEntries` for that.
 */
data class ListCodeLists(
    val tenantId: TenantId,
    val searchTerm: String? = null,
    val catalogKey: CatalogKey? = null,
    val sort: SortSpec = CODE_LIST_SORT.default,
    val page: PageRequest = PageRequest(page = 1, size = 50),
) : Query<PagedResult<CodeList>>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = tenantId.key
}

/** Row shape: the full [CodeList] (mapped via @Nested, so the Secret/JOIN mappers keep working) + total. */
private data class CodeListRow(
    @Nested val codeList: CodeList,
    val totalCount: Long,
)

@Component
class ListCodeListsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListCodeLists, PagedResult<CodeList>> {
    override fun handle(query: ListCodeLists): PagedResult<CodeList> = jdbi.withHandle<PagedResult<CodeList>, Exception> { handle ->
        val sql = buildString {
            append(
                """
                SELECT cl.slug, cl.tenant_key, cl.catalog_key, cl.display_name, cl.description,
                       cl.source_type, cl.source_url, cl.auth_type, cl.credential,
                       cl.last_refreshed_at, cl.last_refresh_error,
                       cl.created_at, cl.updated_at,
                       c.type AS catalog_type,
                       COUNT(*) OVER() AS total_count
                FROM code_lists cl
                JOIN catalogs c ON c.tenant_key = cl.tenant_key AND c.id = cl.catalog_key
                WHERE cl.tenant_key = :tenantId
                """.trimIndent(),
            )
            if (query.catalogKey != null) {
                append(" AND cl.catalog_key = :catalogKey")
            }
            if (!query.searchTerm.isNullOrBlank()) {
                // CAST(slug AS text), not `slug::text` — `::` collides with JDBI's `:name` parser.
                append(" AND (cl.display_name ILIKE :searchTerm OR CAST(cl.slug AS text) ILIKE :searchTerm)")
            }
            // (catalog_key, slug) tiebreaker: slug alone is unique only within a catalog, but
            // this lists across all catalogs, so a bare slug can tie and break deterministic paging.
            append(" ORDER BY ${CODE_LIST_SORT.orderBy(query.sort, tiebreaker = "cl.catalog_key, cl.slug")}")
            append(" LIMIT :limit OFFSET :offset")
        }

        handle.pagedQuery<CodeListRow, CodeList>(
            sql = sql,
            page = query.page,
            bind = { jdbiQuery ->
                jdbiQuery.bind("tenantId", query.tenantId.key)
                if (query.catalogKey != null) {
                    jdbiQuery.bind("catalogKey", query.catalogKey)
                }
                if (!query.searchTerm.isNullOrBlank()) {
                    jdbiQuery.bind("searchTerm", ilikeContains(query.searchTerm))
                }
                jdbiQuery
            },
            totalOf = { it.totalCount },
            map = { it.codeList },
        )
    }
}

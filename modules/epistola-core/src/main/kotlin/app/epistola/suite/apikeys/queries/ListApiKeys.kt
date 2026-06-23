package app.epistola.suite.apikeys.queries

import app.epistola.suite.apikeys.ApiKey
import app.epistola.suite.apikeys.toApiKey
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
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Allowed sort columns: logical key → fixed SQL expression. Anything outside this
 * whitelist falls back to the default rather than reaching `ORDER BY`. See ADR 0007.
 */
private val API_KEY_SORT = SortWhitelist(
    columns = mapOf(
        "name" to "k.name",
        "created" to "k.created_at",
        "lastUsed" to "k.last_used_at",
        "expires" to "k.expires_at",
    ),
    default = SortSpec("created", SortDirection.DESC),
)

/**
 * Lists a tenant's API keys. Only **enabled** keys are returned — a revoked key is disabled,
 * not deleted, so the list view never shows it (the filter lives in SQL, not the caller).
 */
data class ListApiKeys(
    val tenantId: TenantKey,
    val searchTerm: String? = null,
    val sort: SortSpec = API_KEY_SORT.default,
    val page: PageRequest = PageRequest(page = 1, size = 50),
) : Query<PagedResult<ApiKey>>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_USERS
    override val tenantKey get() = tenantId
}

@Component
class ListApiKeysHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListApiKeys, PagedResult<ApiKey>> {

    override fun handle(query: ListApiKeys): PagedResult<ApiKey> = jdbi.withHandle<PagedResult<ApiKey>, Exception> { handle ->
        val sql = buildString {
            append(
                """
                SELECT k.id, k.tenant_key, k.name, k.key_prefix, k.enabled, k.created_at,
                       k.last_used_at, k.expires_at, k.created_by, k.revoked_at, k.revoked_by, k.roles,
                       COALESCE(u.display_name, u.email) AS created_by_display_name,
                       COUNT(*) OVER() AS total_count
                FROM api_keys k
                LEFT JOIN users u ON u.id = k.created_by
                WHERE k.tenant_key = :tenantId AND k.enabled
                """.trimIndent(),
            )
            if (!query.searchTerm.isNullOrBlank()) {
                append(" AND k.name ILIKE :searchTerm")
            }
            // k.id tiebreaker keeps offset paging deterministic when the sort column ties.
            append(" ORDER BY ${API_KEY_SORT.orderBy(query.sort, tiebreaker = "k.id")}")
            append(" LIMIT :limit OFFSET :offset")
        }

        handle.pagedQuery(
            sql = sql,
            page = query.page,
            bind = { jdbiQuery ->
                jdbiQuery.bind("tenantId", query.tenantId)
                if (!query.searchTerm.isNullOrBlank()) {
                    jdbiQuery.bind("searchTerm", "%${query.searchTerm}%")
                }
                jdbiQuery
            },
            mapRow = { it.toApiKey(withDisplayName = true) },
        )
    }
}

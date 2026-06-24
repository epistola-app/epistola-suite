package app.epistola.suite.environments.queries

import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.common.paging.PageRequest
import app.epistola.suite.common.paging.PagedResult
import app.epistola.suite.common.paging.SortDirection
import app.epistola.suite.common.paging.SortSpec
import app.epistola.suite.common.paging.SortWhitelist
import app.epistola.suite.common.paging.ilikeContains
import app.epistola.suite.common.paging.pagedQuery
import app.epistola.suite.environments.Environment
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Allowed sort columns: logical key → fixed SQL expression. Anything outside this
 * whitelist falls back to the default rather than reaching `ORDER BY`. See ADR 0007.
 */
private val ENVIRONMENT_SORT = SortWhitelist(
    columns = mapOf(
        "name" to "name",
        "id" to "id",
        "created" to "created_at",
    ),
    default = SortSpec("name", SortDirection.ASC),
)

data class ListEnvironments(
    val tenantId: TenantId,
    val searchTerm: String? = null,
    val sort: SortSpec = ENVIRONMENT_SORT.default,
    val page: PageRequest = PageRequest(page = 1, size = 50),
) : Query<PagedResult<Environment>>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey get() = tenantId.key
}

/** Row shape including the windowed total (`COUNT(*) OVER()`); see [pagedQuery]. */
private data class EnvironmentRow(
    val id: EnvironmentKey,
    val tenantKey: TenantKey,
    val name: String,
    val createdAt: OffsetDateTime,
    val createdBy: UserKey?,
    val updatedBy: UserKey?,
    val totalCount: Long,
) {
    fun toEnvironment() = Environment(
        id = id,
        tenantKey = tenantKey,
        name = name,
        createdAt = createdAt,
        createdBy = createdBy,
        updatedBy = updatedBy,
    )
}

@Component
class ListEnvironmentsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListEnvironments, PagedResult<Environment>> {
    override fun handle(query: ListEnvironments): PagedResult<Environment> = jdbi.withHandle<PagedResult<Environment>, Exception> { handle ->
        val sql = buildString {
            append(
                """
                SELECT id, tenant_key, name, created_at, created_by, updated_by,
                       COUNT(*) OVER() AS total_count
                FROM environments
                WHERE tenant_key = :tenantId
                """.trimIndent(),
            )
            if (!query.searchTerm.isNullOrBlank()) {
                // id is the ENVIRONMENT_KEY domain; CAST to text so ILIKE resolves (matches
                // the pre-conversion LOWER(id) behaviour that also forced it to text). CAST(),
                // not `id::text` — `::` collides with JDBI's `:name` parameter parser. No
                // explicit ESCAPE clause: Postgres' default LIKE escape is already '\' (the
                // char `ilikeContains` escapes with), and a paired `ESCAPE '\'` confuses JDBI's
                // string-literal tracking across the two ILIKEs.
                append(" AND (name ILIKE :searchTerm OR CAST(id AS text) ILIKE :searchTerm)")
            }
            // id tiebreaker keeps offset paging deterministic when the sort column ties.
            append(" ORDER BY ${ENVIRONMENT_SORT.orderBy(query.sort, tiebreaker = "id")}")
            append(" LIMIT :limit OFFSET :offset")
        }

        handle.pagedQuery<EnvironmentRow, Environment>(
            sql = sql,
            page = query.page,
            bind = { jdbiQuery ->
                jdbiQuery.bind("tenantId", query.tenantId.key)
                if (!query.searchTerm.isNullOrBlank()) {
                    jdbiQuery.bind("searchTerm", ilikeContains(query.searchTerm))
                }
                jdbiQuery
            },
            totalOf = { it.totalCount },
            map = { it.toEnvironment() },
        )
    }
}

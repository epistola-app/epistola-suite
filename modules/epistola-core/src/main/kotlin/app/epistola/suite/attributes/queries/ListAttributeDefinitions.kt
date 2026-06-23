package app.epistola.suite.attributes.queries

import app.epistola.suite.attributes.model.VariantAttributeDefinition
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
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.mapper.Nested
import org.springframework.stereotype.Component

/**
 * Allowed sort columns: logical key → fixed SQL expression. Anything outside this
 * whitelist falls back to the default rather than reaching `ORDER BY`. See ADR 0007.
 */
private val ATTRIBUTE_SORT = SortWhitelist(
    columns = mapOf(
        "name" to "a.display_name",
        "id" to "a.id",
        "created" to "a.created_at",
    ),
    default = SortSpec("name", SortDirection.ASC),
)

data class ListAttributeDefinitions(
    val tenantId: TenantId,
    val searchTerm: String? = null,
    val catalogKey: CatalogKey? = null,
    val sort: SortSpec = ATTRIBUTE_SORT.default,
    val page: PageRequest = PageRequest(page = 1, size = 50),
) : Query<PagedResult<VariantAttributeDefinition>>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = tenantId.key
}

/** Row shape: the full definition (mapped via @Nested, so @Json allowed_values keeps working) + total. */
private data class AttributeRow(
    @Nested val definition: VariantAttributeDefinition,
    val totalCount: Long,
)

@Component
class ListAttributeDefinitionsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListAttributeDefinitions, PagedResult<VariantAttributeDefinition>> {
    override fun handle(query: ListAttributeDefinitions): PagedResult<VariantAttributeDefinition> = jdbi.withHandle<PagedResult<VariantAttributeDefinition>, Exception> { handle ->
        val sql = buildString {
            append(
                """
                    SELECT a.id, a.tenant_key, a.catalog_key, c.type AS catalog_type, a.display_name, a.allowed_values,
                           a.code_list_catalog_key, a.code_list_slug,
                           a.created_at, a.updated_at,
                           COUNT(*) OVER() AS total_count
                    FROM variant_attribute_definitions a
                    JOIN catalogs c ON c.tenant_key = a.tenant_key AND c.id = a.catalog_key
                    WHERE a.tenant_key = :tenantId
                """.trimIndent(),
            )
            if (query.catalogKey != null) {
                append(" AND a.catalog_key = :catalogKey")
            }
            if (!query.searchTerm.isNullOrBlank()) {
                // CAST(id AS text), not `id::text` — `::` collides with JDBI's `:name` parser.
                append(" AND (a.display_name ILIKE :searchTerm OR CAST(a.id AS text) ILIKE :searchTerm)")
            }
            // a.id tiebreaker keeps offset paging deterministic when the sort column ties.
            append(" ORDER BY ${ATTRIBUTE_SORT.orderBy(query.sort, tiebreaker = "a.id")}")
            append(" LIMIT :limit OFFSET :offset")
        }

        handle.pagedQuery<AttributeRow, VariantAttributeDefinition>(
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
            map = { it.definition },
        )
    }
}

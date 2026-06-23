package app.epistola.suite.templates.queries

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateKey
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
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Summary of a template with variant statistics for list views.
 */
data class TemplateSummary(
    val id: TemplateKey,
    val catalogKey: CatalogKey,
    val catalogType: CatalogType = CatalogType.AUTHORED,
    val name: String,
    val updatedAt: OffsetDateTime,
    val variantCount: Int,
    val hasDraft: Boolean,
    val publishedVersionCount: Int,
)

/**
 * Allowed sort columns: logical key → fixed SQL expression. `ORDER BY` cannot be
 * a bind parameter, so anything outside this whitelist is rejected (falls back to
 * the default) rather than reaching SQL. See ADR 0007.
 */
private val TEMPLATE_SORT = SortWhitelist(
    columns = mapOf(
        "name" to "dt.name",
        "updated" to "dt.updated_at",
        "variants" to "variant_count",
        "published" to "published_version_count",
    ),
    default = SortSpec("updated", SortDirection.DESC),
)
private val DEFAULT_TEMPLATE_SORT = TEMPLATE_SORT.default

data class ListTemplateSummaries(
    val tenantId: TenantId,
    val searchTerm: String? = null,
    val catalogKey: CatalogKey? = null,
    val sort: SortSpec = DEFAULT_TEMPLATE_SORT,
    val page: PageRequest = PageRequest(page = 1, size = 50),
) : Query<PagedResult<TemplateSummary>>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = tenantId.key
}

/**
 * Row shape including the windowed total. `total_count` is `COUNT(*) OVER()`, the
 * size of the full filtered set before the page window (same value on every row).
 */
private data class TemplateSummaryRow(
    val id: TemplateKey,
    val catalogKey: CatalogKey,
    val catalogType: CatalogType,
    val name: String,
    val updatedAt: OffsetDateTime,
    val variantCount: Int,
    val hasDraft: Boolean,
    val publishedVersionCount: Int,
    val totalCount: Long,
) {
    fun toSummary() = TemplateSummary(
        id = id,
        catalogKey = catalogKey,
        catalogType = catalogType,
        name = name,
        updatedAt = updatedAt,
        variantCount = variantCount,
        hasDraft = hasDraft,
        publishedVersionCount = publishedVersionCount,
    )
}

@Component
class ListTemplateSummariesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListTemplateSummaries, PagedResult<TemplateSummary>> {
    override fun handle(query: ListTemplateSummaries): PagedResult<TemplateSummary> = jdbi.withHandle<PagedResult<TemplateSummary>, Exception> { handle ->
        val sql = buildString {
            append(
                """
                SELECT
                    dt.id,
                    dt.catalog_key,
                    c.type AS catalog_type,
                    dt.name,
                    dt.updated_at,
                    COALESCE((SELECT COUNT(*) FROM template_variants tv WHERE tv.tenant_key = dt.tenant_key AND tv.template_key = dt.id), 0)::int as variant_count,
                    COALESCE((SELECT bool_or(ver.status = 'draft')
                              FROM template_versions ver
                              JOIN template_variants tv ON ver.tenant_key = tv.tenant_key AND ver.template_key = tv.template_key AND ver.variant_key = tv.id
                              WHERE tv.tenant_key = dt.tenant_key AND tv.template_key = dt.id), false) as has_draft,
                    COALESCE((SELECT COUNT(*)
                              FROM template_versions ver
                              JOIN template_variants tv ON ver.tenant_key = tv.tenant_key AND ver.template_key = tv.template_key AND ver.variant_key = tv.id
                              WHERE tv.tenant_key = dt.tenant_key AND tv.template_key = dt.id AND ver.status = 'published'), 0)::int as published_version_count,
                    COUNT(*) OVER() AS total_count
                FROM document_templates dt
                JOIN catalogs c ON c.tenant_key = dt.tenant_key AND c.id = dt.catalog_key
                WHERE dt.tenant_key = :tenantId
                """.trimIndent(),
            )
            if (query.catalogKey != null) {
                append(" AND dt.catalog_key = :catalogKey")
            }
            if (!query.searchTerm.isNullOrBlank()) {
                append(" AND dt.name ILIKE :searchTerm ESCAPE '\\'")
            }
            // dt.id tiebreaker keeps offset paging deterministic when the sort column ties.
            append(" ORDER BY ${TEMPLATE_SORT.orderBy(query.sort, tiebreaker = "dt.id")}")
            append(" LIMIT :limit OFFSET :offset")
        }

        handle.pagedQuery<TemplateSummaryRow, TemplateSummary>(
            sql = sql,
            page = query.page,
            bind = { jdbiQuery ->
                jdbiQuery.bind("tenantId", query.tenantId.key)
                if (query.catalogKey != null) {
                    jdbiQuery.bind("catalogKey", query.catalogKey)
                }
                if (!query.searchTerm.isNullOrBlank()) {
                    val escaped = query.searchTerm.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                    jdbiQuery.bind("searchTerm", "%$escaped%")
                }
                jdbiQuery
            },
            totalOf = { it.totalCount },
            map = { it.toSummary() },
        )
    }
}

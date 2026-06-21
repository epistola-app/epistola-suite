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
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
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
 * [DEFAULT_TEMPLATE_SORT]) rather than reaching SQL. See ADR 0007.
 */
private val SORTABLE_TEMPLATE_COLUMNS = mapOf(
    "name" to "dt.name",
    "updated" to "dt.updated_at",
    "variants" to "variant_count",
    "published" to "published_version_count",
)
private val DEFAULT_TEMPLATE_SORT = SortSpec("updated", SortDirection.DESC)

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
            val resolvedSort = if (SORTABLE_TEMPLATE_COLUMNS.containsKey(query.sort.column)) query.sort else DEFAULT_TEMPLATE_SORT
            val orderColumn = SORTABLE_TEMPLATE_COLUMNS.getValue(resolvedSort.column)
            val orderDirection = if (resolvedSort.direction == SortDirection.ASC) "ASC" else "DESC"
            // dt.id tiebreaker keeps offset paging deterministic when the sort column ties.
            append(" ORDER BY $orderColumn $orderDirection, dt.id ASC")
            append(" LIMIT :limit OFFSET :offset")
        }

        val size = query.page.size

        fun fetch(offset: Int): List<TemplateSummaryRow> {
            val jdbiQuery = handle.createQuery(sql)
                .bind("tenantId", query.tenantId.key)
            if (query.catalogKey != null) {
                jdbiQuery.bind("catalogKey", query.catalogKey)
            }
            if (!query.searchTerm.isNullOrBlank()) {
                val escaped = query.searchTerm.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                jdbiQuery.bind("searchTerm", "%$escaped%")
            }
            return jdbiQuery
                .bind("limit", size)
                .bind("offset", offset)
                .mapTo<TemplateSummaryRow>()
                .list()
        }

        var page = query.page.page
        var rows = fetch((page - 1) * size)

        // The windowed COUNT(*) returns no rows on an out-of-range page (e.g. a stale
        // bookmark), so we can't read the total there. Re-fetch page 1 to learn the
        // total, clamp to the last page, and fetch that. Only happens on a bad page.
        if (rows.isEmpty() && page > 1) {
            val firstPage = fetch(0)
            val total = firstPage.firstOrNull()?.totalCount ?: 0L
            val lastPage = if (total == 0L) 1 else ((total + size - 1) / size).toInt()
            page = lastPage
            rows = when {
                total == 0L -> emptyList()
                lastPage == 1 -> firstPage
                else -> fetch((lastPage - 1) * size)
            }
        }

        PagedResult(
            items = rows.map { it.toSummary() },
            page = page,
            size = size,
            total = rows.firstOrNull()?.totalCount ?: 0L,
        )
    }
}

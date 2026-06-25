package app.epistola.suite.templates.queries

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
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
 * One page of [TemplateSummary] rows plus [total], the count of all rows
 * matching the same filters (ignoring paging). Both come from a single query
 * (`COUNT(*) OVER()`), so the count can never disagree with the rows the page
 * renders — there is no second query to drift out of sync.
 */
data class TemplateSummaryPage(
    val items: List<TemplateSummary>,
    val total: Int,
)

/**
 * Sortable columns for the templates list. The [column] strings are a fixed
 * whitelist baked into the SQL — never interpolate raw user input into ORDER BY.
 *
 * @property param The stable query-parameter value used in UI links.
 * @property defaultDescending The natural direction when the column is first selected.
 */
enum class TemplateSort(
    val param: String,
    val column: String,
    val defaultDescending: Boolean,
) {
    NAME("name", "dt.name", false),
    CATALOG("catalog", "dt.catalog_key", false),
    VARIANTS("variants", "variant_count", true),
    UPDATED("updated", "dt.updated_at", true),
    ;

    companion object {
        fun fromParam(param: String?): TemplateSort = entries.find { it.param == param } ?: UPDATED
    }
}

data class ListTemplateSummaries(
    val tenantId: TenantId,
    val searchTerm: String? = null,
    val catalogKey: CatalogKey? = null,
    val sort: TemplateSort = TemplateSort.UPDATED,
    val descending: Boolean = true,
    val limit: Int = 50,
    val offset: Int = 0,
) : Query<TemplateSummaryPage>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = tenantId.key
}

@Component
class ListTemplateSummariesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListTemplateSummaries, TemplateSummaryPage> {
    override fun handle(query: ListTemplateSummaries): TemplateSummaryPage = jdbi.withHandle<TemplateSummaryPage, Exception> { handle ->
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
                    -- Total matching rows before LIMIT/OFFSET; Postgres evaluates the
                    -- window over the full filtered set, so it cannot drift from the page.
                    COUNT(*) OVER()::int AS total_count
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
            // ORDER BY column comes from the TemplateSort whitelist, never raw input.
            val direction = if (query.descending) "DESC" else "ASC"
            append(" ORDER BY ${query.sort.column} $direction, dt.catalog_key ASC, dt.id ASC")
            append(" LIMIT :limit OFFSET :offset")
        }

        val jdbiQuery = handle.createQuery(sql)
            .bind("tenantId", query.tenantId.key)
        if (query.catalogKey != null) {
            jdbiQuery.bind("catalogKey", query.catalogKey)
        }
        if (!query.searchTerm.isNullOrBlank()) {
            val escaped = query.searchTerm.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            jdbiQuery.bind("searchTerm", "%$escaped%")
        }
        val rows = jdbiQuery
            .bind("limit", query.limit)
            .bind("offset", query.offset)
            .mapTo<TemplateSummaryRow>()
            .list()

        // An out-of-range page returns zero rows, so there is no window value to
        // read — total is 0 there and the caller re-queries a valid page.
        TemplateSummaryPage(
            items = rows.map { it.toSummary() },
            total = rows.firstOrNull()?.totalCount ?: 0,
        )
    }
}

/**
 * Row shape for [ListTemplateSummariesHandler]: a [TemplateSummary] plus the
 * `COUNT(*) OVER()` window total carried on every returned row.
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
    val totalCount: Int,
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

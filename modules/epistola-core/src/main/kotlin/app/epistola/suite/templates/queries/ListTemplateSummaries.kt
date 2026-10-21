// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.queries

import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
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
    val name: String,
    val updatedAt: OffsetDateTime,
    val variantCount: Int,
    val ready: Boolean,
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
    CATALOG("catalog", "c.catalog_key", false),
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
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogVersionRef? = null,
) : CatalogVersionScoped,
    Query<TemplateSummaryPage>,
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
                    c.catalog_key AS catalog_key,
                    dt.name,
                    dt.updated_at,
                    COALESCE((SELECT COUNT(*) FROM template_variants tv WHERE tv.tenant_key = dt.tenant_key AND tv.version_id = dt.version_id AND tv.template_resource_id = dt.resource_id), 0)::int as variant_count,
                    -- Ready when every variant's content is marked ready as it is now.
                    COALESCE((SELECT bool_and(m.digest IS NOT NULL AND m.digest = d.digest)
                              FROM resource_content_digests d
                              LEFT JOIN resource_ready_marks m ON m.tenant_key = d.tenant_key AND m.version_id = d.version_id
                                                                AND m.resource_id = d.resource_id AND m.part = d.part
                              WHERE d.tenant_key = dt.tenant_key AND d.version_id = dt.version_id AND d.resource_id = dt.resource_id), false) as ready,
                    -- Total matching rows before LIMIT/OFFSET; Postgres evaluates the
                    -- window over the full filtered set, so it cannot drift from the page.
                    COUNT(*) OVER()::int AS total_count
                FROM document_templates dt
                JOIN catalogs c ON c.tenant_key = dt.tenant_key AND c.catalog_id = dt.catalog_id
                WHERE dt.tenant_key = :tenantId AND ${inCurrentVersion("dt")}
                """.trimIndent(),
            )
            if (query.catalogKey != null) {
                append(" AND dt.version_id = ${versionAt("tenantId", "catalogKey")}")
            }
            if (!query.searchTerm.isNullOrBlank()) {
                append(" AND dt.name ILIKE :searchTerm ESCAPE '\\'")
            }
            // ORDER BY column comes from the TemplateSort whitelist, never raw input.
            val direction = if (query.descending) "DESC" else "ASC"
            append(" ORDER BY ${query.sort.column} $direction, c.catalog_key ASC, dt.id ASC")
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
    val name: String,
    val updatedAt: OffsetDateTime,
    val variantCount: Int,
    val ready: Boolean,
    val totalCount: Int,
) {
    fun toSummary() = TemplateSummary(
        id = id,
        catalogKey = catalogKey,
        name = name,
        updatedAt = updatedAt,
        variantCount = variantCount,
        ready = ready,
    )
}

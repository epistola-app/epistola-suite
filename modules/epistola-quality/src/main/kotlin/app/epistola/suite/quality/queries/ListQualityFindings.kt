// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.queries

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.bindVersion
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.catalog.releases.addressedVersion
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.quality.EffectiveQualityStatus
import app.epistola.suite.quality.QualityFindingPage
import app.epistola.suite.quality.QualitySeverity
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Sortable columns for the quality report. The [column] strings are a fixed whitelist baked into the
 * SQL — never interpolate raw user input into ORDER BY.
 */
enum class QualityFindingSort(
    val param: String,
    val column: String,
    val defaultDescending: Boolean,
) {
    /** Worst first. Ordered by rank, not alphabetically — 'ERROR' < 'INFO' < 'WARNING' as text. */
    SEVERITY("severity", "CASE x.severity WHEN 'ERROR' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END", false),
    TEMPLATE("template", "x.template_key", false),
    RULE("rule", "x.rule_id", false),
    LAST_SEEN("lastSeen", "x.last_seen_at", true),
    FIRST_SEEN("firstSeen", "x.first_seen_at", true),
    ;

    companion object {
        fun fromParam(param: String?): QualityFindingSort = entries.find { it.param == param } ?: LAST_SEEN
    }
}

/**
 * The tenant-wide quality report: every finding the version each catalog means shows (its next
 * version, unless a message targets another; ADR 0044), from every source, filtered and paged.
 *
 * ### Effective status is filtered in SQL, deliberately
 *
 * `IGNORED` is derived (from a live ignore row), not stored — so filtering by it means filtering on
 * a computed value. Doing that in Kotlin would mean fetching every finding in the tenant to render
 * one page of 50, and a `total` that disagreed with the rows. Instead the derivation happens in a
 * subselect and the filter, the `COUNT(*) OVER()` and the LIMIT/OFFSET all sit outside it, so
 * Postgres does the work and the count can never drift from the page.
 */
data class ListQualityFindings(
    override val tenantKey: TenantKey,
    val catalog: CatalogId? = null,
    /** The version of [catalog] to report on: a draft or a release; null for each catalog's next version. */
    override val catalogVersion: CatalogReleaseRef?,
    val templateKey: TemplateKey? = null,
    val sourceId: QualitySourceId? = null,
    val ruleId: String? = null,
    val severity: QualitySeverity? = null,
    /** Null shows everything, including resolved. The UI defaults to OPEN. */
    val status: EffectiveQualityStatus? = null,
    val searchTerm: String? = null,
    val sort: QualityFindingSort = QualityFindingSort.LAST_SEEN,
    val descending: Boolean = true,
    val limit: Int = 50,
    val offset: Int = 0,
) : Query<QualityFindingPage>,
    ReleaseReadable,
    RequiresPermission {
    init {
        validate("catalog", catalog == null || catalog.tenantKey == tenantKey) { "The catalog is of the tenant" }
        validate("catalogVersion", catalogVersion == null || catalogVersion.catalog == catalog) { "The version is of the catalog reported on" }
    }

    val catalogKey: CatalogKey? get() = catalog?.key
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
}

@Component
class ListQualityFindingsHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<ListQualityFindings, QualityFindingPage> {
    override fun handle(query: ListQualityFindings): QualityFindingPage = jdbi.withHandle<QualityFindingPage, Exception> { handle ->
        // A catalog filter narrows the checks too, so only that catalog's variants have their key computed.
        val checks = "k.tenant_key = :tenantKey" + if (query.catalogKey != null) " AND k.catalog_id = ${catalogAt("tenantKey", "catalogKey")}" else ""
        val sql = buildString {
            append(
                """
                SELECT x.*, COUNT(*) OVER()::int AS total_count
                FROM (
                    SELECT s.*, ${QualityFindingSql.columns("s")}
                    FROM (${QualityFindingSql.shown(checks, "f.tenant_key = :tenantKey")}) s
                    ${QualityFindingSql.decisions("s")}
                    WHERE s.tenant_key = :tenantKey
                """.trimIndent(),
            )
            if (query.catalogKey != null) append(" AND template.catalog_id = ${catalogAt("tenantKey", "catalogKey")}")
            if (query.templateKey != null) append(" AND template.id = :templateKey")
            if (query.sourceId != null) append(" AND s.source_id = :sourceId")
            if (query.ruleId != null) append(" AND s.rule_id = :ruleId")
            if (query.severity != null) append(" AND s.severity = :severity")
            if (!query.searchTerm.isNullOrBlank()) append(" AND s.message ILIKE :searchTerm ESCAPE '\\'")
            append(") x")
            // Outside the subselect, because effective_status does not exist inside it. The window
            // total is evaluated after this filter, so it always matches the rows returned.
            if (query.status != null) append(" WHERE x.effective_status = :status")
            // ORDER BY column comes from the QualityFindingSort whitelist, never raw input.
            val direction = if (query.descending) "DESC" else "ASC"
            append(" ORDER BY ${query.sort.column} $direction, x.id ASC")
            append(" LIMIT :limit OFFSET :offset")
        }

        val jdbiQuery = handle.createQuery(sql)
            .bind("tenantKey", query.tenantKey)
            .bind("manualSourceId", QualitySourceId.MANUAL.value)
            .bindVersion(QualityFindingSql.VERSION, handle.addressedVersion(query))
        query.catalog?.let { jdbiQuery.bindCatalog("catalogKey", it) }
        query.templateKey?.let { jdbiQuery.bind("templateKey", it) }
        query.sourceId?.let { jdbiQuery.bind("sourceId", it.value) }
        query.ruleId?.let { jdbiQuery.bind("ruleId", it) }
        query.severity?.let { jdbiQuery.bind("severity", it.name) }
        query.status?.let { jdbiQuery.bind("status", it.name) }
        if (!query.searchTerm.isNullOrBlank()) {
            val escaped = query.searchTerm.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            jdbiQuery.bind("searchTerm", "%$escaped%")
        }

        val rows = jdbiQuery
            .bind("limit", query.limit)
            .bind("offset", query.offset)
            .map { rs, _ -> mapFinding(rs, objectMapper) to rs.getInt("total_count") }
            .list()

        QualityFindingPage(
            items = rows.map { it.first },
            // An out-of-range page returns no rows, so there is no window value to read.
            total = rows.firstOrNull()?.second ?: 0,
        )
    }
}

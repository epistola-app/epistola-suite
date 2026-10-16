// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.queries

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.StencilReferences
import app.epistola.suite.stencils.StencilResolvers
import app.epistola.suite.stencils.model.StencilInstances
import app.epistola.suite.stencils.model.StencilUsageDetail
import app.epistola.suite.stencils.model.StencilUsagePage
import app.epistola.suite.templates.model.TemplateDocument
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

private const val USAGE_PAGE_SIZE = 100

/**
 * A page of the template versions (draft and published) that use a stencil, each with whether it
 * still fits the stencil's working copy. Pagination is in SQL; the fit is checked for the page.
 */
data class GetStencilUsagePage(
    val stencilId: StencilId,
    val page: Int = 1,
) : Query<StencilUsagePage>,
    RequiresPermission {
    override val permission = Permission.STENCIL_VIEW
    override val tenantKey: TenantKey get() = stencilId.tenantKey
}

private val USAGE_CTE = """
    WITH usage AS (
        SELECT dt.id AS template_key, c.catalog_key AS catalog_key, c.type AS catalog_type, dt.name AS template_name,
               tv.variant_key, tv.id AS version_id, tv.status AS version_status, tv.template_model::text AS template_model,
               COUNT(*) AS instance_count
        FROM template_versions tv
        JOIN document_templates dt ON dt.tenant_key = tv.tenant_key AND dt.resource_id = tv.template_resource_id
        JOIN catalogs c ON c.tenant_key = dt.tenant_key AND c.catalog_id = dt.catalog_id
        CROSS JOIN LATERAL jsonb_each(tv.template_model -> 'nodes') AS node(key, value)
        WHERE tv.tenant_key = :tenantId
          AND tv.status IN ('draft', 'published')
          AND ${StencilReferences.usedBy("node", "dt")}
        GROUP BY dt.id, c.catalog_key, c.type, dt.name, tv.variant_key, tv.id, tv.status, tv.template_model::text
    )
"""

@Component
class GetStencilUsagePageHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val stencilResolvers: StencilResolvers,
) : QueryHandler<GetStencilUsagePage, StencilUsagePage> {
    override fun handle(query: GetStencilUsagePage): StencilUsagePage = jdbi.withHandle<StencilUsagePage, Exception> { handle ->
        val total = handle.createQuery("$USAGE_CTE SELECT COUNT(*) FROM usage")
            .bind("tenantId", query.stencilId.tenantKey)
            .bind(StencilReferences.KEY_PARAM, query.stencilId.key.value)
            .bind(StencilReferences.CATALOG_PARAM, query.stencilId.catalogKey.value)
            .mapTo(Int::class.java)
            .one()
        val totalPages = if (total == 0) 1 else (total + USAGE_PAGE_SIZE - 1) / USAGE_PAGE_SIZE
        val current = query.page.coerceIn(1, totalPages)
        val resolver = stencilResolvers.workingCopies(query.stencilId.tenantKey)

        val items = handle.createQuery(
            """
            $USAGE_CTE
            SELECT * FROM usage
            ORDER BY (CASE WHEN catalog_type = 'AUTHORED' THEN 0 ELSE 1 END), catalog_key, template_name, variant_key, version_id DESC
            LIMIT :limit OFFSET :offset
            """,
        )
            .bind("tenantId", query.stencilId.tenantKey)
            .bind(StencilReferences.KEY_PARAM, query.stencilId.key.value)
            .bind(StencilReferences.CATALOG_PARAM, query.stencilId.catalogKey.value)
            .bind("limit", USAGE_PAGE_SIZE)
            .bind("offset", (current - 1) * USAGE_PAGE_SIZE)
            .map { rs, _ ->
                val catalogKey = rs.getString("catalog_key")
                val document = objectMapper.readValue(rs.getString("template_model"), TemplateDocument::class.java)
                val problems = StencilInstances.expand(document, catalogKey, resolver).findings
                    .filter { it.stencilKey == query.stencilId.key.value && it.catalogKey == query.stencilId.catalogKey.value && it.blocking }
                    .map(::describe)
                StencilUsageDetail(
                    templateId = TemplateKey.of(rs.getString("template_key")),
                    catalogKey = CatalogKey.of(catalogKey),
                    catalogType = CatalogType.valueOf(rs.getString("catalog_type")),
                    templateName = rs.getString("template_name"),
                    variantId = VariantKey.of(rs.getString("variant_key")),
                    versionId = VersionKey.of(rs.getInt("version_id")),
                    versionStatus = rs.getString("version_status"),
                    instanceCount = rs.getInt("instance_count"),
                    problems = problems,
                )
            }
            .list()

        StencilUsagePage(items = items, page = current, totalPages = totalPages, total = total)
    }

    private fun describe(finding: StencilInstances.Finding): String = when (finding.problem) {
        StencilInstances.Problem.UNKNOWN_PLACEHOLDER -> "fills placeholder '${finding.name}', which the stencil no longer has"
        StencilInstances.Problem.UNBOUND_REQUIRED_PARAMETER -> "does not bind required parameter '${finding.name}'"
        StencilInstances.Problem.UNKNOWN_PARAMETER -> "binds parameter '${finding.name}', which the stencil does not declare"
        StencilInstances.Problem.STENCIL_NOT_FOUND -> "uses a stencil that does not exist"
        StencilInstances.Problem.RECURSION -> "contains itself"
        StencilInstances.Problem.DEPTH_EXCEEDED -> "nests stencils too deeply"
    }
}

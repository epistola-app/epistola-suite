// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.queries

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.StencilReferences
import app.epistola.suite.stencils.StencilResolvers
import app.epistola.suite.stencils.model.StencilInstances
import app.epistola.suite.stencils.model.StencilUsageDetail
import app.epistola.suite.stencils.model.StencilUsagePage
import app.epistola.suite.stencils.model.describe
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

private const val USAGE_PAGE_SIZE = 100

/**
 * A page of the template variants that use a stencil, each with whether its working model still
 * fits the stencil's working copy. Pagination is in SQL; the fit is checked for the page.
 * [page] is 1-based and clamped to the pages there are; [pageSize] is at most [USAGE_PAGE_SIZE].
 */
data class GetStencilUsagePage(
    val stencilId: StencilId,
    val page: Int = 1,
    val pageSize: Int = USAGE_PAGE_SIZE,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Query<StencilUsagePage>,
    RequiresPermission {
    override val permission = Permission.STENCIL_VIEW
    override val tenantKey: TenantKey get() = stencilId.tenantKey

    init {
        validate("pageSize", pageSize in 1..USAGE_PAGE_SIZE) { "Page size must be between 1 and $USAGE_PAGE_SIZE" }
    }
}

private val USAGE_CTE = """
    WITH usage AS (
        SELECT dt.id AS template_key, c.catalog_key AS catalog_key, c.namespace AS namespace, dt.name AS template_name,
               COALESCE(c.source_url LIKE 'classpath:%', FALSE) AS supplied,
               tv.id AS variant_key, tv.template_model::text AS template_model, dt.data_model::text AS data_model,
               COUNT(*) AS instance_count
        FROM current_template_variants tv
        JOIN current_templates dt ON dt.tenant_key = tv.tenant_key AND dt.catalog_id = tv.catalog_id
                                  AND dt.resource_id = tv.template_resource_id
        JOIN catalogs c ON c.tenant_key = dt.tenant_key AND c.catalog_id = dt.catalog_id
        CROSS JOIN LATERAL jsonb_each(tv.template_model -> 'nodes') AS node(key, value)
        WHERE tv.tenant_key = :tenantId
          AND ${StencilReferences.usedBy("node", "dt")}
        GROUP BY dt.id, c.catalog_key, c.namespace, c.source_url, dt.name, tv.id, tv.template_model::text, dt.data_model::text
    )
"""

@Component
class GetStencilUsagePageHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val stencilResolvers: StencilResolvers,
    private val dependencyViews: app.epistola.suite.catalog.dependencies.DraftDependencyViews,
) : QueryHandler<GetStencilUsagePage, StencilUsagePage> {
    override fun handle(query: GetStencilUsagePage): StencilUsagePage = jdbi.withHandle<StencilUsagePage, Exception> { handle ->
        val total = handle.createQuery("$USAGE_CTE SELECT COUNT(*) FROM usage")
            .bind("tenantId", query.stencilId.tenantKey)
            .bind(StencilReferences.KEY_PARAM, query.stencilId.key.value)
            .bindCatalog(StencilReferences.CATALOG_PARAM, query.stencilId.catalogId)
            .mapTo(Int::class.java)
            .one()
        val totalPages = if (total == 0) 1 else (total + query.pageSize - 1) / query.pageSize
        val current = query.page.coerceIn(1, totalPages)
        // Each template's instances resolve from its own catalog's declarations (ADR 0034, section 9).
        val resolvers = mutableMapOf<app.epistola.suite.common.ids.CatalogId, StencilInstances.Resolver>()

        val items = handle.createQuery(
            """
            $USAGE_CTE
            SELECT * FROM usage
            -- Uses in catalogs that can be changed here first: a catalog Epistola supplies cannot
            -- (ADR 0032), and the installation's switch holds for every catalog alike.
            ORDER BY supplied, catalog_key, namespace, template_name, variant_key
            LIMIT :limit OFFSET :offset
            """,
        )
            .bind("tenantId", query.stencilId.tenantKey)
            .bind(StencilReferences.KEY_PARAM, query.stencilId.key.value)
            .bindCatalog(StencilReferences.CATALOG_PARAM, query.stencilId.catalogId)
            .bind("limit", query.pageSize)
            .bind("offset", (current - 1) * query.pageSize)
            .map { rs, _ ->
                val catalogKey = rs.getString("catalog_key")
                val templateCatalog = app.epistola.suite.common.ids.CatalogId(rs.getString("namespace"), CatalogKey.of(catalogKey), query.stencilId.catalogId.tenantId)
                val resolver = resolvers.getOrPut(templateCatalog) { stencilResolvers.workingCopies(dependencyViews.of(templateCatalog)) }
                val document = app.epistola.suite.templates.TemplateModels.read(rs.getString("template_model"), catalogKey, objectMapper)
                val dataModel = rs.getString("data_model")?.let { objectMapper.readValue(it, Map::class.java) }?.mapKeys { (key, _) -> key as String }
                val problems = StencilInstances.expand(document, templateCatalog.address, resolver, dataModel).findings
                    .filter { it.stencilKey == query.stencilId.key.value && it.catalogKey in setOf(query.stencilId.catalogKey.value, query.stencilId.catalogId.address) && it.blocking }
                    .map { it.describe() }
                StencilUsageDetail(
                    templateId = TemplateKey.of(rs.getString("template_key")),
                    catalogKey = CatalogKey.of(catalogKey),
                    namespace = rs.getString("namespace"),
                    templateName = rs.getString("template_name"),
                    variantId = VariantKey.of(rs.getString("variant_key")),
                    instanceCount = rs.getInt("instance_count"),
                    problems = problems,
                )
            }
            .list()

        StencilUsagePage(items = items, page = current, totalPages = totalPages, total = total)
    }
}

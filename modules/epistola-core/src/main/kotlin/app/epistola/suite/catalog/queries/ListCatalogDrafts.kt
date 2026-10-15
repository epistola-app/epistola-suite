// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * The unpublished work in an authored catalog: everything a release would not yet contain.
 *
 * Interim, until the working copy and its ready mark exist (WP3 of docs/catalog-release-model-v2.md):
 * a release reads each variant's, contract's and stencil's latest *published* version, so a draft is
 * exactly the work that is not ready.
 */
data class CatalogDrafts(
    /** Template variants with a draft version, ordered by template and variant. */
    val variants: List<Pair<TemplateKey, VariantKey>>,
    /** Templates whose data contract has a draft. */
    val contracts: List<TemplateKey>,
    /** Stencils with a draft version. */
    val stencils: List<StencilKey>,
) {
    /** Template slugs with any draft: in a variant or in the contract. */
    val templates: Set<String> get() = (variants.map { it.first.value } + contracts.map { it.value }).toSet()
}

data class ListCatalogDrafts(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<CatalogDrafts>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class ListCatalogDraftsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListCatalogDrafts, CatalogDrafts> {
    override fun handle(query: ListCatalogDrafts): CatalogDrafts = jdbi.withHandle<CatalogDrafts, Exception> { handle ->
        val variants = handle.createQuery(
            """
            SELECT DISTINCT dt.id AS template_key, tv.variant_key
            FROM template_versions tv
            JOIN document_templates dt ON dt.tenant_key = tv.tenant_key AND dt.resource_id = tv.template_resource_id
            WHERE tv.tenant_key = :t AND dt.catalog_key = :c AND tv.status = 'draft'
            ORDER BY dt.id, tv.variant_key
            """,
        )
            .bind("t", query.tenantKey)
            .bind("c", query.catalogKey)
            .map { rs, _ -> TemplateKey.of(rs.getString("template_key")) to VariantKey.of(rs.getString("variant_key")) }
            .list()
        val contracts = handle.createQuery(
            """
            SELECT DISTINCT dt.id FROM contract_versions cv
            JOIN document_templates dt ON dt.tenant_key = cv.tenant_key AND dt.resource_id = cv.template_resource_id
            WHERE cv.tenant_key = :t AND dt.catalog_key = :c AND cv.status = 'draft'
            ORDER BY dt.id
            """,
        )
            .bind("t", query.tenantKey)
            .bind("c", query.catalogKey)
            .map { rs, _ -> TemplateKey.of(rs.getString(1)) }
            .list()
        val stencils = handle.createQuery(
            """
            SELECT DISTINCT s.id FROM stencil_versions sv
            JOIN stencils s ON s.tenant_key = sv.tenant_key AND s.resource_id = sv.stencil_resource_id
            WHERE sv.tenant_key = :t AND s.catalog_key = :c AND sv.status = 'draft'
            ORDER BY s.id
            """,
        )
            .bind("t", query.tenantKey)
            .bind("c", query.catalogKey)
            .map { rs, _ -> StencilKey.of(rs.getString(1)) }
            .list()
        CatalogDrafts(variants, contracts, stencils)
    }
}

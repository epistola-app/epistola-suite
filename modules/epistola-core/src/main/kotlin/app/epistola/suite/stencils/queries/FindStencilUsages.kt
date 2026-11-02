// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.queries

import app.epistola.suite.catalog.VERSION_ARGS
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.StencilReferences
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Finds the names of the templates and stencils that use a given stencil, from any catalog of the
 * tenant. Used to check if a stencil is in use before deletion: a stencil may contain stencils, of its
 * own catalog or of one it declares.
 */
data class FindStencilUsages(
    val stencilId: StencilId,
) : Query<List<String>>,
    RequiresPermission {
    override val permission get() = Permission.STENCIL_VIEW
    override val tenantKey: TenantKey get() = stencilId.tenantKey
}

@Component
class FindStencilUsagesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<FindStencilUsages, List<String>> {

    override fun handle(query: FindStencilUsages): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT dt.name
            FROM current_template_variants(${VERSION_ARGS}) tv
            JOIN current_templates(${VERSION_ARGS}) dt ON dt.tenant_key = tv.tenant_key AND dt.catalog_id = tv.catalog_id
                                      AND dt.resource_id = tv.template_resource_id
            CROSS JOIN LATERAL jsonb_each(tv.template_model -> 'nodes') AS n(key, value)
            WHERE tv.tenant_key = :tenantId
              AND ${StencilReferences.usedBy("n", "dt")}
            UNION
            SELECT s.name
            FROM current_stencils(${VERSION_ARGS}) s
            CROSS JOIN LATERAL jsonb_each(s.content -> 'nodes') AS n(key, value)
            WHERE s.tenant_key = :tenantId
              AND ${StencilReferences.usedBy("n", "s")}
            ORDER BY 1
            """,
        )
            .bind("tenantId", query.stencilId.tenantKey)
            .bind(StencilReferences.KEY_PARAM, query.stencilId.key.value)
            .bindCatalog(StencilReferences.CATALOG_PARAM, query.stencilId.catalogId)
            .mapTo(String::class.java)
            .list()
    }
}

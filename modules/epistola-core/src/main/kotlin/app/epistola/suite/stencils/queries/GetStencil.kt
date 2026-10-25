// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.queries

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.STENCIL_COLUMNS
import app.epistola.suite.stencils.STENCIL_FROM
import app.epistola.suite.stencils.Stencil
import app.epistola.suite.stencils.withAbsentSchemaAsNull
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetStencil(
    val id: StencilId,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : ReleaseReadable,
    Query<Stencil?>,
    RequiresPermission {
    override val permission = Permission.STENCIL_VIEW
    override val tenantKey: TenantKey get() = id.tenantKey
}

@Component
class GetStencilHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetStencil, Stencil?> {
    override fun handle(query: GetStencil): Stencil? = jdbi.withHandle<Stencil?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT $STENCIL_COLUMNS
            $STENCIL_FROM
            WHERE s.id = :id AND s.tenant_key = :tenantId AND s.catalog_id = ${catalogAt("tenantId", "catalogKey")}
            """,
        )
            .bind("id", query.id.key)
            .bind("tenantId", query.id.tenantKey)
            .bindCatalog("catalogKey", query.id.catalogId)
            .mapTo<Stencil>()
            .map(Stencil::withAbsentSchemaAsNull)
            .findOne()
            .orElse(null)
    }
}

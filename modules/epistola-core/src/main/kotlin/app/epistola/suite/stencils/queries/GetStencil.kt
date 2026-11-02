// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.queries

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.bindVersion
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.catalog.releases.versionOf
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.STENCIL_COLUMNS
import app.epistola.suite.stencils.Stencil
import app.epistola.suite.stencils.asRead
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetStencil(
    val id: StencilId,
    /** The catalog version this addresses; null for its catalog's next version. */
    override val catalogVersion: CatalogReleaseRef?,
) : ReleaseReadable,
    Query<Stencil?>,
    RequiresPermission {
    override val permission = Permission.STENCIL_VIEW
    override val tenantKey: TenantKey get() = id.tenantKey
}

@Component
class GetStencilHandler(
    private val jdbi: Jdbi,
    private val objectMapper: tools.jackson.databind.ObjectMapper,
) : QueryHandler<GetStencil, Stencil?> {
    override fun handle(query: GetStencil): Stencil? = jdbi.withHandle<Stencil?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT $STENCIL_COLUMNS
            FROM version_stencils s
            WHERE s.id = :id AND s.tenant_key = :tenantId AND s.version_id = :v
            """,
        )
            .bind("id", query.id.key)
            .bind("tenantId", query.id.tenantKey)
            .bindVersion("v", handle.versionOf(query.id.catalogId, query).versionId)
            .mapTo<Stencil>()
            .map { it.asRead(objectMapper) }
            .findOne()
            .orElse(null)
    }
}

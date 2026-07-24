// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.queries

import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Total embedded-instance count per stencil version across the tenant's draft and
 * published templates (archived template versions are historical and excluded).
 * Backs the "Uses" column on the stencil Versions table. Versions with no live use
 * are simply absent from the map.
 */
data class CountStencilUsageByVersion(
    val stencilId: StencilId,
) : Query<Map<Int, Int>>,
    RequiresPermission {
    override val permission get() = Permission.STENCIL_VIEW
    override val tenantKey: TenantKey get() = stencilId.tenantKey
}

@Component
class CountStencilUsageByVersionHandler(
    private val jdbi: Jdbi,
) : QueryHandler<CountStencilUsageByVersion, Map<Int, Int>> {
    override fun handle(query: CountStencilUsageByVersion): Map<Int, Int> = jdbi.withHandle<Map<Int, Int>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT (n.value -> 'props' ->> 'version')::int AS version, COUNT(*) AS uses
            FROM template_versions tv
            CROSS JOIN LATERAL jsonb_each(tv.template_model -> 'nodes') AS n(key, value)
            WHERE tv.tenant_key = :tenantId
              AND tv.status IN ('draft', 'published')
              AND n.value ->> 'type' = 'stencil'
              AND n.value -> 'props' ->> 'stencilId' = :stencilId
              AND n.value -> 'props' ->> 'version' ~ '^[0-9]+$'
            GROUP BY (n.value -> 'props' ->> 'version')::int
            """,
        )
            .bind("tenantId", query.stencilId.tenantKey)
            .bind("stencilId", query.stencilId.key.value)
            .map { rs, _ -> rs.getInt("version") to rs.getInt("uses") }
            .list()
            .toMap()
    }
}

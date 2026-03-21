package app.epistola.suite.themes.queries

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Counts the number of themes for a tenant.
 * Used to prevent deleting the last theme.
 */
data class CountThemesForTenant(
    val tenantId: TenantId,
) : Query<Long>,
    RequiresPermission {
    override val permission get() = Permission.THEME_VIEW
    override val tenantKey get() = tenantId.key
}

@Component
class CountThemesForTenantHandler(
    private val jdbi: Jdbi,
) : QueryHandler<CountThemesForTenant, Long> {
    override fun handle(query: CountThemesForTenant): Long = jdbi.withHandle<Long, Exception> { handle ->
        handle.createQuery(
            """
            SELECT COUNT(*) FROM themes WHERE tenant_key = :tenantId
            """,
        )
            .bind("tenantId", query.tenantId.key)
            .mapTo<Long>()
            .one()
    }
}

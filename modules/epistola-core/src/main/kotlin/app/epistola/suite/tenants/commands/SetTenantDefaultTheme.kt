package app.epistola.suite.tenants.commands

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.themes.ThemeNotFoundException
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Sets the default theme for a tenant.
 * The theme must exist and belong to the tenant.
 */
data class SetTenantDefaultTheme(
    val tenantId: TenantId,
    val themeId: ThemeId,
) : Command<Tenant>

@Component
class SetTenantDefaultThemeHandler(
    private val jdbi: Jdbi,
) : CommandHandler<SetTenantDefaultTheme, Tenant> {
    override fun handle(command: SetTenantDefaultTheme): Tenant = jdbi.withHandle<Tenant, Exception> { handle ->
        // Verify the theme exists and belongs to this tenant
        val themeExists = handle.createQuery(
            """
            SELECT COUNT(*) FROM themes WHERE id = :themeId AND tenant_id = :tenantId
            """,
        )
            .bind("themeId", command.themeId)
            .bind("tenantId", command.tenantId)
            .mapTo<Long>()
            .one() > 0

        if (!themeExists) {
            throw ThemeNotFoundException(command.themeId)
        }

        // Update the tenant's default theme
        handle.createQuery(
            """
            UPDATE tenants
            SET default_theme_id = :themeId
            WHERE id = :tenantId
            RETURNING *
            """,
        )
            .bind("tenantId", command.tenantId)
            .bind("themeId", command.themeId)
            .mapTo<Tenant>()
            .one()
    }
}

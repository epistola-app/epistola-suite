package app.epistola.suite.tenants.commands

import app.epistola.suite.common.EntityIdentifiable
import app.epistola.suite.common.TenantScoped
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Routable
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.themes.ThemeNotFoundException
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Sets the default theme for a tenant.
 * The theme must exist and belong to the tenant.
 */
data class SetTenantDefaultTheme(
    override val tenantId: TenantKey,
    val themeId: ThemeKey,
) : Command<Tenant>,
    TenantScoped,
    EntityIdentifiable,
    Routable,
    RequiresPermission {
    override val permission = Permission.TENANT_SETTINGS
    override val tenantKey: TenantKey get() = tenantId
    override val entityId: String get() = tenantId.value
    override val routingKey: String get() = tenantId.value
}

@Component
class SetTenantDefaultThemeHandler(
    private val jdbi: Jdbi,
) : CommandHandler<SetTenantDefaultTheme, Tenant> {
    @Transactional
    override fun handle(command: SetTenantDefaultTheme): Tenant = jdbi.withHandle<Tenant, Exception> { handle ->
        // Verify the theme exists and belongs to this tenant
        val themeExists = handle.createQuery(
            "SELECT COUNT(*) FROM themes WHERE id = :themeId AND tenant_key = :tenantId",
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
            SET default_theme_key = :themeId
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

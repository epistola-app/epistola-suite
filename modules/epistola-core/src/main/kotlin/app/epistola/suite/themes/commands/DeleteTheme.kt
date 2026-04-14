package app.epistola.suite.themes.commands

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.themes.LastThemeException
import app.epistola.suite.themes.ThemeInUseException
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Deletes a theme by ID.
 *
 * Constraints:
 * - Cannot delete the tenant's current default theme
 * - Cannot delete the last theme for a tenant (at least one must exist)
 *
 * Templates referencing this theme will gracefully fall back to their own styles.
 */
data class DeleteTheme(
    val id: ThemeId,
) : Command<Boolean>,
    RequiresPermission {
    override val permission = Permission.THEME_EDIT
    override val tenantKey: TenantKey get() = id.tenantKey
}

@Component
class DeleteThemeHandler(
    private val jdbi: Jdbi,
) : CommandHandler<DeleteTheme, Boolean> {
    /**
     * Deletes a theme by ID.
     * Returns true if a theme was deleted, false if not found.
     * @throws ThemeInUseException if the theme is the tenant's default theme
     * @throws LastThemeException if this is the last theme for the tenant
     */
    override fun handle(command: DeleteTheme): Boolean {
        requireCatalogEditable(command.id.tenantKey, command.id.catalogKey)
        return jdbi.withHandle<Boolean, Exception> { handle ->
            // Check if this is the tenant's default theme
            val isDefaultTheme = handle.createQuery(
                """
            SELECT COUNT(*) FROM tenants
            WHERE id = :tenantId AND default_theme_key = :themeId
            """,
            )
                .bind("tenantId", command.id.tenantKey)
                .bind("themeId", command.id.key)
                .mapTo<Long>()
                .one() > 0

            if (isDefaultTheme) {
                throw ThemeInUseException(command.id.key, "it is the tenant's default theme")
            }

            // Check if this is the last theme for the tenant
            val themeCount = handle.createQuery(
                """
            SELECT COUNT(*) FROM themes WHERE tenant_key = :tenantId
            """,
            )
                .bind("tenantId", command.id.tenantKey)
                .mapTo<Long>()
                .one()

            if (themeCount <= 1) {
                throw LastThemeException(command.id.key)
            }

            // Delete the theme
            val deleted = handle.createUpdate(
                """
            DELETE FROM themes WHERE id = :id AND tenant_key = :tenantId
            """,
            )
                .bind("id", command.id.key)
                .bind("tenantId", command.id.tenantKey)
                .execute()
            deleted > 0
        }
    }
}

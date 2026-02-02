package app.epistola.suite.themes.commands

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
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
    val tenantId: TenantId,
    val id: ThemeId,
) : Command<Boolean>

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
    override fun handle(command: DeleteTheme): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        // Check if this is the tenant's default theme
        val isDefaultTheme = handle.createQuery(
            """
            SELECT COUNT(*) FROM tenants
            WHERE id = :tenantId AND default_theme_id = :themeId
            """,
        )
            .bind("tenantId", command.tenantId)
            .bind("themeId", command.id)
            .mapTo<Long>()
            .one() > 0

        if (isDefaultTheme) {
            throw ThemeInUseException(command.id, "it is the tenant's default theme")
        }

        // Check if this is the last theme for the tenant
        val themeCount = handle.createQuery(
            """
            SELECT COUNT(*) FROM themes WHERE tenant_id = :tenantId
            """,
        )
            .bind("tenantId", command.tenantId)
            .mapTo<Long>()
            .one()

        if (themeCount <= 1) {
            throw LastThemeException(command.id)
        }

        // Delete the theme
        val deleted = handle.createUpdate(
            """
            DELETE FROM themes WHERE id = :id AND tenant_id = :tenantId
            """,
        )
            .bind("id", command.id)
            .bind("tenantId", command.tenantId)
            .execute()
        deleted > 0
    }
}

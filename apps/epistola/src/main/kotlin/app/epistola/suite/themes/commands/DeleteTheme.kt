package app.epistola.suite.themes.commands

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Deletes a theme by ID.
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
     */
    override fun handle(command: DeleteTheme): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
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

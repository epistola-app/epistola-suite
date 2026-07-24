// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes.commands

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.themes.ThemeInUseException
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Deletes a theme by ID.
 *
 * Constraint: cannot delete the tenant's current default theme — clear the
 * tenant default first (themes are optional, so zero themes is a valid state).
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
     */
    override fun handle(command: DeleteTheme): Boolean {
        requireCatalogEditable(command.id.tenantKey, command.id.catalogKey)
        return jdbi.withHandle<Boolean, Exception> { handle ->
            val isDefaultTheme = handle.createQuery(
                """
                SELECT COUNT(*) FROM tenants
                WHERE id = :tenantId
                  AND default_theme_key = :themeId
                  AND default_theme_catalog_key = :catalogKey
                """,
            )
                .bind("tenantId", command.id.tenantKey)
                .bind("themeId", command.id.key)
                .bind("catalogKey", command.id.catalogKey)
                .mapTo<Long>()
                .one() > 0

            if (isDefaultTheme) {
                throw ThemeInUseException(command.id.key, "it is the tenant's default theme")
            }

            val deleted = handle.createUpdate(
                """
                DELETE FROM themes WHERE id = :id AND tenant_key = :tenantId AND catalog_key = :catalogKey
                """,
            )
                .bind("id", command.id.key)
                .bind("tenantId", command.id.tenantKey)
                .bind("catalogKey", command.id.catalogKey)
                .execute()
            deleted > 0
        }
    }
}

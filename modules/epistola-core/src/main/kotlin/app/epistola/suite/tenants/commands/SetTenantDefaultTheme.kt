// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.tenants.commands

import app.epistola.suite.common.EntityIdentifiable
import app.epistola.suite.common.TenantScoped
import app.epistola.suite.common.ids.CatalogKey
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
 * Sets (or clears) the default theme for a tenant.
 *
 * - `themeId = null` clears the tenant default — templates without a theme will
 *   then render with engine defaults.
 * - When `themeId != null`, the theme must exist within `catalogKey` for this
 *   tenant.
 */
data class SetTenantDefaultTheme(
    override val tenantId: TenantKey,
    val themeId: ThemeKey?,
    val catalogKey: CatalogKey? = null,
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
        if (command.themeId == null) {
            return@withHandle handle.createQuery(
                """
                UPDATE tenants
                SET default_theme_key = NULL,
                    default_theme_catalog_key = NULL
                WHERE id = :tenantId
                RETURNING *
                """,
            )
                .bind("tenantId", command.tenantId)
                .mapTo<Tenant>()
                .one()
        }

        val catalogKey = command.catalogKey ?: CatalogKey.DEFAULT

        val themeExists = handle.createQuery(
            """
            SELECT COUNT(*) FROM themes
            WHERE id = :themeId AND tenant_key = :tenantId AND catalog_key = :catalogKey
            """,
        )
            .bind("themeId", command.themeId)
            .bind("tenantId", command.tenantId)
            .bind("catalogKey", catalogKey)
            .mapTo<Long>()
            .one() > 0

        if (!themeExists) {
            throw ThemeNotFoundException(command.themeId)
        }

        handle.createQuery(
            """
            UPDATE tenants
            SET default_theme_key = :themeId,
                default_theme_catalog_key = :catalogKey
            WHERE id = :tenantId
            RETURNING *
            """,
        )
            .bind("tenantId", command.tenantId)
            .bind("themeId", command.themeId)
            .bind("catalogKey", catalogKey)
            .mapTo<Tenant>()
            .one()
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.tenants.commands

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.EntityIdentifiable
import app.epistola.suite.common.TenantScoped
import app.epistola.suite.common.ids.CatalogId
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
import app.epistola.suite.themes.themeAtAddress
import app.epistola.suite.themes.updateTenantReturningTenant
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Sets (or clears) the default theme for a tenant.
 *
 * - `themeId = null` clears the tenant default — templates without a theme will
 *   then render with engine defaults.
 * - When `themeId != null`, `catalogKey` names the catalog the theme lives in
 *   (required) and the theme must exist there for this tenant.
 */
data class SetTenantDefaultTheme(
    val tenantId: TenantKey,
    val catalog: CatalogId? = null,
    val themeId: ThemeKey?,
) : Command<Tenant>,
    TenantScoped,
    EntityIdentifiable,
    Routable,
    RequiresPermission {
    init {
        validate("catalog", catalog == null || catalog.tenantKey == tenantId) { "The catalog is of the tenant" }
    }

    val catalogKey: CatalogKey? get() = catalog?.key
    override val permission = Permission.TENANT_SETTINGS
    override val tenantKey: TenantKey get() = tenantId
    override val entityId: String get() = tenantId.value
    override val routingKey: String get() = tenantId.value

    init {
        validate("catalogKey", themeId == null || catalogKey != null) { "A default theme needs the catalog it lives in" }
    }
}

@Component
class SetTenantDefaultThemeHandler(
    private val jdbi: Jdbi,
) : CommandHandler<SetTenantDefaultTheme, Tenant> {
    @Transactional
    override fun handle(command: SetTenantDefaultTheme): Tenant = jdbi.withHandle<Tenant, Exception> { handle ->
        if (command.themeId == null) {
            return@withHandle handle.createQuery(
                updateTenantReturningTenant("default_theme_resource_id = NULL", "id = :tenantId"),
            )
                .bind("tenantId", command.tenantId)
                .mapTo<Tenant>()
                .one()
        }

        val catalog = checkNotNull(command.catalog) { "validated in init" }

        val themeExists = handle.createQuery(
            """
            SELECT COUNT(*) FROM current_themes
            WHERE id = :themeId AND tenant_key = :tenantId AND catalog_id = ${catalogAt("tenantId", "catalogKey")}
            """,
        )
            .bind("themeId", command.themeId)
            .bind("tenantId", command.tenantId)
            .bindCatalog("catalogKey", catalog)
            .mapTo<Long>()
            .one() > 0

        if (!themeExists) {
            throw ThemeNotFoundException(command.themeId)
        }

        handle.createQuery(
            updateTenantReturningTenant(
                "default_theme_resource_id = ${themeAtAddress("tenantId", "catalogKey", "themeId")}",
                "id = :tenantId",
            ),
        )
            .bind("tenantId", command.tenantId)
            .bind("themeId", command.themeId)
            .bindCatalog("catalogKey", catalog)
            .mapTo<Tenant>()
            .one()
    }
}

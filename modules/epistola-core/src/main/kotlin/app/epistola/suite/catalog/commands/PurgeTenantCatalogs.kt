package app.epistola.suite.catalog.commands

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Deletes **all** of a tenant's catalogs and their resources. Core owns this because it owns the
 * catalog schema and its foreign-key topology — callers (e.g. a snapshot restore) must not encode
 * that knowledge themselves.
 *
 * Deletion order respects the cross-catalog FKs that a bare `DELETE FROM catalogs` would trip:
 *  1. Null `tenants.default_theme_key` — the `fk_tenants_default_theme` FK is `NO ACTION`, so a
 *     tenant pointing its default theme at a catalog theme would block the cascade. (MATCH SIMPLE:
 *     nulling the key disables the composite FK.) The prior value is returned so the caller can
 *     re-point it after re-importing.
 *  2. Delete `variant_attribute_definitions` — the attribute→code-list cross-catalog FK is
 *     `ON DELETE RESTRICT`.
 *  3. `DELETE FROM catalogs` — every other tenant resource cascades.
 *
 * Runs in the caller's transaction (JDBI joins the ambient Spring transaction), so a destructive
 * restore can wrap this + the re-import in one atomic unit.
 */
data class PurgeTenantCatalogs(
    override val tenantKey: TenantKey,
) : Command<PurgeTenantCatalogsResult>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS
}

/** The tenant's default-theme pointer as it was before the purge, so the caller can restore it. */
data class PurgeTenantCatalogsResult(
    val priorDefaultThemeCatalogKey: String?,
    val priorDefaultThemeKey: String?,
)

@Component
class PurgeTenantCatalogsHandler(
    private val jdbi: Jdbi,
) : CommandHandler<PurgeTenantCatalogs, PurgeTenantCatalogsResult> {
    override fun handle(command: PurgeTenantCatalogs): PurgeTenantCatalogsResult = jdbi.withHandle<PurgeTenantCatalogsResult, Exception> { handle ->
        val prior =
            handle
                .createQuery("SELECT default_theme_catalog_key, default_theme_key FROM tenants WHERE id = :tenantKey")
                .bind("tenantKey", command.tenantKey)
                .map { rs, _ ->
                    PurgeTenantCatalogsResult(rs.getString("default_theme_catalog_key"), rs.getString("default_theme_key"))
                }.findOne()
                .orElse(PurgeTenantCatalogsResult(null, null))

        handle
            .createUpdate("UPDATE tenants SET default_theme_key = NULL WHERE id = :tenantKey")
            .bind("tenantKey", command.tenantKey)
            .execute()
        handle
            .createUpdate("DELETE FROM variant_attribute_definitions WHERE tenant_key = :tenantKey")
            .bind("tenantKey", command.tenantKey)
            .execute()
        handle
            .createUpdate("DELETE FROM catalogs WHERE tenant_key = :tenantKey")
            .bind("tenantKey", command.tenantKey)
            .execute()

        prior
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.catalog.dependencies.UsedCatalogDeclarer
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * The tenant's house theme (contract ADR 0007, decision 1a): one place to say "this is our theme".
 * It is never read while rendering; a catalog takes it as its own default theme, which its published
 * versions then carry.
 */
object HouseTheme {
    /**
     * Makes the house theme the default theme of [catalogKey]'s next version, when the tenant has one.
     *
     * @return the catalog the house theme lives in, which [catalogKey] now uses; null when the tenant
     *   has no house theme or [catalogKey] has no next version.
     */
    fun applyTo(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): CatalogKey? {
        val themeCatalog = handle.createQuery(
            """
            SELECT tc.catalog_key FROM tenants t
            JOIN catalog_resources r ON r.tenant_key = t.id AND r.resource_id = t.default_theme_resource_id
            JOIN catalogs tc ON tc.tenant_key = r.tenant_key AND tc.catalog_id = r.catalog_id
            WHERE t.id = :t
            """,
        )
            .bind("t", tenantKey)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null) ?: return null
        val updated = handle.createUpdate(
            """
            UPDATE catalog_versions v SET default_theme_resource_id = (SELECT default_theme_resource_id FROM tenants WHERE id = :t)
            FROM catalogs c
            WHERE c.tenant_key = v.tenant_key AND c.catalog_id = v.catalog_id
              AND v.tenant_key = :t AND c.catalog_key = :c AND c.type = 'AUTHORED' AND v.is_next
            """,
        )
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .execute()
        return CatalogKey.of(themeCatalog).takeIf { updated > 0 }
    }
}

/** Which catalogs [ApplyHouseTheme] gave the house theme, and which it could not (no next version). */
data class ApplyHouseThemeResult(val applied: List<CatalogKey>, val skipped: List<CatalogKey>)

/**
 * Makes the house theme the default theme of every catalog the tenant authors, in each one's next
 * version; each takes it into a published version on its next publish. Does nothing without a house
 * theme.
 */
data class ApplyHouseTheme(
    override val tenantKey: TenantKey,
) : Command<ApplyHouseThemeResult>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS
}

@Component
class ApplyHouseThemeHandler(
    private val jdbi: Jdbi,
    private val usedCatalogs: UsedCatalogDeclarer,
) : CommandHandler<ApplyHouseTheme, ApplyHouseThemeResult> {
    override fun handle(command: ApplyHouseTheme): ApplyHouseThemeResult {
        val outcome = jdbi.inTransaction<List<Pair<CatalogKey, CatalogKey?>>, Exception> { handle ->
            handle.createQuery("SELECT catalog_key FROM catalogs WHERE tenant_key = :t AND type = 'AUTHORED' ORDER BY catalog_key")
                .bind("t", command.tenantKey)
                .mapTo(String::class.java)
                .list()
                .map { CatalogKey.of(it) }
                .map { catalog -> catalog to HouseTheme.applyTo(handle, command.tenantKey, catalog) }
        }
        // A house theme of another catalog is a use of that catalog, declared as any reference is.
        outcome.forEach { (catalog, themeCatalog) ->
            themeCatalog?.takeIf { it != catalog }?.let { usedCatalogs.declare(command.tenantKey, catalog, it) }
        }
        return ApplyHouseThemeResult(
            applied = outcome.filter { it.second != null }.map { it.first },
            skipped = outcome.filter { it.second == null }.map { it.first },
        )
    }
}

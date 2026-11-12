// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.bindVersion
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.dependencies.UsedCatalogDeclarer
import app.epistola.suite.catalog.dependencies.catalogUses
import app.epistola.suite.catalog.isNext
import app.epistola.suite.catalog.queries.ListCatalogEditability
import app.epistola.suite.catalog.releases.ContentVersion
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_NAMESPACE
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
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
     * Makes the house theme the default theme of draft [at], a catalog's next version, when the
     * tenant has one. The caller decides that its catalog can be changed (ADR 0032).
     *
     * @return the catalog the house theme lives in, which [at] now uses; null when the tenant has no
     *   house theme or the catalog has no next version.
     */
    fun applyTo(handle: Handle, at: ContentVersion): CatalogId? {
        val catalog = at.catalog
        handle.writable(at)
        val themeCatalog = handle.createQuery(
            """
            SELECT tc.namespace, tc.catalog_key FROM tenants t
            JOIN catalog_resources r ON r.tenant_key = t.id AND r.resource_id = t.default_theme_resource_id
            JOIN catalogs tc ON tc.tenant_key = r.tenant_key AND tc.catalog_id = r.catalog_id
            WHERE t.id = :t
            """,
        )
            .bind("t", catalog.tenantKey)
            .map { rs, _ -> CatalogId(rs.getString("namespace"), CatalogKey.of(rs.getString("catalog_key")), catalog.tenantId) }
            .findOne()
            .orElse(null) ?: return null
        val updated = handle.createUpdate(
            """
            UPDATE catalog_versions v SET default_theme_resource_id = (SELECT default_theme_resource_id FROM tenants WHERE id = :t)
            WHERE v.tenant_key = :t AND v.version_id = :v
            """,
        )
            .bind("t", catalog.tenantKey)
            .bindVersion("v", at.versionId)
            .execute()
        return themeCatalog.takeIf { updated > 0 }
    }
}

/** Which catalogs [ApplyHouseTheme] gave the house theme, and which it could not (no next version). */
data class ApplyHouseThemeResult(val applied: List<CatalogId>, val skipped: List<CatalogId>)

/**
 * Makes the house theme the default theme of every catalog of the tenant that can be changed here
 * (ADR 0032), in each one's next version; each takes it into a published version on its next
 * publish. Does nothing without a house theme.
 *
 * The catalog the replaced default theme came from stays declared only while something else in the
 * version uses it. A declaration left behind would still be pinned, and, naming that catalog's
 * draft, hold publishing until that catalog is published too, for a theme nothing uses any more.
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
    private val contentBuilder: CatalogContentBuilder,
    private val dependencyStore: CatalogDependencyStore,
) : CommandHandler<ApplyHouseTheme, ApplyHouseThemeResult> {
    override fun handle(command: ApplyHouseTheme): ApplyHouseThemeResult {
        val editable = ListCatalogEditability(command.tenantKey).query().editableCatalogs.sortedWith(compareBy({ it.key.value }, { it.namespace }))
        // Two passes. The first gives every catalog the house theme and drops what the theme it replaced
        // alone used; only then is the theme's catalog declared. In one pass, a catalog declaring the
        // new theme's catalog while that catalog still declares it, for the theme it is replacing, would
        // be refused as catalogs using each other.
        val applied = editable.map { catalog ->
            // Each catalog's next version: the house theme is the default, by name.
            val at = jdbi.withHandle<ContentVersion, Exception> { ContentVersion.next(it, catalog) }
            // The catalog the default theme it replaces came from, by the alias the version names it by.
            val replacedFrom = contentBuilder.build(at).catalog.defaultTheme?.catalogKey
            val themeCatalog = jdbi.inTransaction<CatalogId?, Exception> { handle -> HouseTheme.applyTo(handle, at) }
            if (themeCatalog != null && replacedFrom != null) dropIfUnused(at, replacedFrom)
            Triple(catalog, at, themeCatalog)
        }
        val outcome = applied.map { (catalog, at, themeCatalog) ->
            // A house theme of another catalog is a use of that catalog, declared as any reference is.
            themeCatalog?.takeIf { it != catalog }?.let { usedCatalogs.declare(at, it) }
            catalog to themeCatalog
        }
        return ApplyHouseThemeResult(
            applied = outcome.filter { it.second != null }.map { it.first },
            skipped = outcome.filter { it.second == null }.map { it.first },
        )
    }

    /**
     * Removes version [at]'s declaration of [alias] when nothing in it uses that catalog any more
     * (as `CatalogBorrowing.unused` judges it). The system catalog stays: every catalog declares it
     * from the start, used or not.
     */
    private fun dropIfUnused(at: ContentVersion, alias: String) {
        if (catalogUses(contentBuilder.build(at)).any { it.first == alias }) return
        jdbi.useTransaction<Exception> { handle ->
            val declarations = dependencyStore.declarations(handle, at)
            val declaration = declarations.firstOrNull { it.alias == alias } ?: return@useTransaction
            if (declaration.namespace == SYSTEM_CATALOG_NAMESPACE && declaration.catalogKey == SYSTEM_CATALOG_KEY.value) return@useTransaction
            dependencyStore.replace(handle, at, declarations - declaration)
        }
    }
}

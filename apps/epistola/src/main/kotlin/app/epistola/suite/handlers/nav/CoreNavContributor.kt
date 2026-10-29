// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers.nav

import app.epistola.suite.catalog.queries.CountCatalogsWithUpgradeAvailable
import app.epistola.suite.exchange.ResolveCatalogPublishingAvailability
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.htmx.UiRequestContext
import app.epistola.suite.htmx.nav.NavContributor
import app.epistola.suite.htmx.nav.NavGroup
import app.epistola.suite.htmx.nav.NavIndicator
import app.epistola.suite.htmx.nav.NavItem
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import org.springframework.stereotype.Component

/**
 * The host app's own navigation outside a catalog: the tenant's side nav (docs/workspace-shell.md).
 * Templates, stencils, themes and the other parts are not here: they live in a catalog, and the
 * catalog's own nav ([CatalogNav][app.epistola.suite.handlers.CatalogNav]) lists them.
 * The shell puts the catalogs themselves at the top of the Catalogs group.
 *
 * Items are gated by permission here; the aggregator drops any group left with no visible items
 * (so Operations disappears for users with none of its permissions, and Settings for non-managers).
 * Support-tier groups and items are contributed by the support modules.
 */
@Component
class CoreNavContributor : NavContributor {

    override fun groups(context: UiRequestContext): List<NavGroup> = listOf(
        NavGroup(key = "authoring", label = "Catalogs", order = 10, testId = "nav-group-catalogs"),
        NavGroup(key = "documents", label = "Documents", order = 20, testId = "nav-group-documents"),
        NavGroup(key = "operations", label = "Operations", order = 30, testId = "nav-group-operations"),
        NavGroup(key = "settings", label = "Settings", order = 90, testId = "nav-group-settings"),
    )

    override fun items(context: UiRequestContext): List<NavItem> = buildList {
        // Catalogs: the shell lists each catalog above these.
        if (context.hasPermission(Permission.CATALOG_VIEW)) {
            add(NavItem("authoring", "catalogs", "All catalogs", "catalogs", 40, indicator = catalogUpdates(context)))
        }

        // Documents: where applications get them, and what was made.
        add(NavItem("documents", "environments", "Environments", "environments", 10))
        if (context.hasPermission(Permission.DOCUMENT_VIEW)) {
            add(NavItem("documents", "generation-history", "Generation", "generation-history", 20))
        }
        if (context.hasPermission(Permission.DOCUMENT_GENERATE)) {
            add(NavItem("documents", "load-tests", "Load Tests", "load-tests", 30))
        }

        // Operations — per-item permissions
        if (context.hasPermission(Permission.DIAGNOSTICS_VIEW)) {
            add(NavItem("operations", "consumers", "Consumers", "consumers", 30))
            add(NavItem("operations", "cluster", "Cluster", "cluster", 35))
            add(NavItem("operations", "logs", "Logs", "logs", 45))
        }
        // The Audit item is contributed by the epistola-audit module's AuditNavContributor.
        if (context.hasPermission(Permission.TENANT_USERS)) {
            add(NavItem("operations", "api-keys", "API Keys", "api-keys", 40))
        }

        // Settings — managers only
        if (context.hasPermission(Permission.TENANT_SETTINGS)) {
            add(NavItem("settings", "features", "Features", "features", 10))
            add(NavItem("settings", "defaults", "Defaults", "defaults", 20))
            if (ResolveCatalogPublishingAvailability(context.tenantKey).query()) {
                add(
                    NavItem(
                        "settings",
                        "exchange",
                        "Exchange",
                        "exchange",
                        30,
                        stage = KnownFeatures.stageOf(KnownFeatures.CATALOG_PUBLISHING),
                    ),
                )
            }
        }
    }

    /**
     * How many catalogs with a source have an update waiting, or null when none do.
     *
     * This runs on every page render for every signed-in user, so it is one indexed count over
     * state the background check already recorded — never a call to anybody's server. It is the
     * whole reason that check persists what it finds instead of asking while a page loads.
     */
    private fun catalogUpdates(context: UiRequestContext): NavIndicator? {
        val waiting = CountCatalogsWithUpgradeAvailable(context.tenantKey).query()
        if (waiting <= 0) return null
        return NavIndicator(
            count = waiting,
            label = if (waiting == 1) "1 catalog has an update available" else "$waiting catalogs have updates available",
        )
    }
}

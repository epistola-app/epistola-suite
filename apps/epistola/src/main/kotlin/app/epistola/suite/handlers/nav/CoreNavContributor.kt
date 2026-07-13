package app.epistola.suite.handlers.nav

import app.epistola.suite.htmx.UiRequestContext
import app.epistola.suite.htmx.nav.NavContributor
import app.epistola.suite.htmx.nav.NavGroup
import app.epistola.suite.htmx.nav.NavItem
import app.epistola.suite.security.Permission
import app.epistola.suite.security.PlatformRole
import org.springframework.stereotype.Component

/**
 * The host app's own navigation: the always-present groups (Authoring, Resources, Operations,
 * Settings). Support-tier groups/items are contributed by the support modules.
 *
 * Items are gated by permission here; the aggregator drops any group left with no visible items
 * (so Operations disappears for users with none of its permissions, and Settings for non-managers).
 */
@Component
class CoreNavContributor : NavContributor {

    override fun groups(context: UiRequestContext): List<NavGroup> = listOf(
        NavGroup(key = "authoring", label = "Authoring", order = 10, testId = "nav-dropdown-authoring"),
        NavGroup(key = "resources", label = "Resources", order = 20, testId = "nav-dropdown-resources"),
        NavGroup(key = "operations", label = "Operations", order = 30, testId = "nav-dropdown-operations"),
        NavGroup(key = "settings", label = "Settings", order = 90, testId = "nav-dropdown-settings"),
    )

    override fun items(context: UiRequestContext): List<NavItem> = buildList {
        // Authoring
        add(NavItem("authoring", "templates", "Templates", "templates", 10))
        add(NavItem("authoring", "themes", "Themes", "themes", 20))
        add(NavItem("authoring", "stencils", "Stencils", "stencils", 30))
        if (context.hasPermission(Permission.CATALOG_VIEW)) {
            add(NavItem("authoring", "catalogs", "Catalogs", "catalogs", 40))
        }

        // Resources
        add(NavItem("resources", "environments", "Environments", "environments", 10))
        add(NavItem("resources", "images", "Images", "images", 20))
        if (context.hasPermission(Permission.REFERENCE_VIEW)) {
            add(NavItem("resources", "fonts", "Fonts", "fonts", 30))
            add(NavItem("resources", "attributes", "Attributes", "attributes", 40))
            add(NavItem("resources", "code-lists", "Code lists", "code-lists", 50))
        }

        // Operations — per-item permissions
        if (context.hasPermission(Permission.DOCUMENT_VIEW)) {
            add(NavItem("operations", "generation-history", "Generation", "generation-history", 10))
        }
        if (context.hasPermission(Permission.DOCUMENT_GENERATE)) {
            add(NavItem("operations", "load-tests", "Load Tests", "load-tests", 20))
        }
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
        }
        // Site banner — installation-wide, platform managers only.
        if (context.hasPlatformRole(PlatformRole.TENANT_MANAGER)) {
            add(NavItem("settings", "site-banner", "Site banner", "site-banner", 30))
        }
    }
}

package app.epistola.suite.htmx.nav

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.htmx.UiRequestContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** A nav item as rendered by the shell template. The href is built from [pathSuffix]. */
data class NavItemView(
    val sectionKey: String,
    val label: String,
    val pathSuffix: String,
    val active: Boolean,
)

/** A dropdown group as rendered by the shell template; [active] when any child is active. */
data class NavGroupView(
    val key: String,
    val label: String,
    val testId: String?,
    val items: List<NavItemView>,
    val active: Boolean,
)

/** The resolved nav model for one request: ordered, non-empty groups + the active section. */
data class NavModel(
    val groups: List<NavGroupView>,
    val activeNavSection: String,
)

/**
 * Builds the navigation model from all registered [NavContributor]s.
 *
 * Groups are merged by key (one contributor declares each group; others only add items),
 * empty groups are dropped (this is what hides the Support menu when no support feature is on),
 * and the active section is derived from the request path by matching contributed item paths —
 * replacing the former hardcoded path→section table.
 */
@Component
class NavMenuAggregator(
    private val contributors: List<NavContributor>,
) {
    fun build(
        tenantKey: TenantKey,
        requestPath: String,
        hasPermission: (String) -> Boolean,
    ): NavModel {
        val ctx = UiRequestContext(tenantKey, hasPermission)

        // Merge group declarations by key (first declaration wins).
        val groupDecls = LinkedHashMap<String, NavGroup>()
        for (contributor in contributors) {
            for (group in contributor.groups(ctx)) {
                groupDecls.putIfAbsent(group.key, group)
            }
        }

        val items = contributors.flatMap { it.items(ctx) }
        val activeSection = resolveActiveSection(requestPath, items)

        val groups = items
            .groupBy { it.groupKey }
            .mapNotNull { (groupKey, groupItems) ->
                val decl = groupDecls[groupKey]
                if (decl == null) {
                    log.warn("Nav item(s) reference unknown group '{}'; skipping. Sections: {}", groupKey, groupItems.map { it.sectionKey })
                    return@mapNotNull null
                }
                val views = groupItems
                    .sortedWith(compareBy({ it.order }, { it.sectionKey }))
                    .map {
                        NavItemView(
                            sectionKey = it.sectionKey,
                            label = it.label,
                            pathSuffix = it.pathSuffix,
                            active = it.sectionKey == activeSection,
                        )
                    }
                NavGroupView(
                    key = decl.key,
                    label = decl.label,
                    testId = decl.testId,
                    items = views,
                    active = views.any { it.active },
                )
            }
            .sortedWith(compareBy({ groupDecls.getValue(it.key).order }, { it.key }))

        return NavModel(groups, activeSection)
    }

    /**
     * Resolves the active section by the longest [NavItem.pathSuffix] that matches the request
     * path at a segment boundary. Longest-match handles nested paths (e.g.
     * `/templates/{cat}/{id}/settings` → `templates`); the boundary check prevents a `code` item
     * from matching `/code-lists`. Falls back to "home".
     */
    private fun resolveActiveSection(requestPath: String, items: List<NavItem>): String {
        val path = requestPath.substringBefore('?')
        return items
            .filter { matchesSegment(path, it.pathSuffix) }
            .maxByOrNull { it.pathSuffix.length }
            ?.sectionKey
            ?: "home"
    }

    private fun matchesSegment(path: String, suffix: String): Boolean {
        val needle = "/$suffix"
        val idx = path.indexOf(needle)
        if (idx < 0) return false
        val after = idx + needle.length
        return after == path.length || path[after] == '/'
    }

    private companion object {
        val log = LoggerFactory.getLogger(NavMenuAggregator::class.java)
    }
}

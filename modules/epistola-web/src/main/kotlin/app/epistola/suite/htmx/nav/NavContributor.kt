package app.epistola.suite.htmx.nav

import app.epistola.suite.htmx.UiRequestContext

/**
 * Module-contributed navigation.
 *
 * The host app no longer hardcodes the nav menu. Instead, every module (the host's
 * [CoreNavContributor] and each feature module) implements [NavContributor] as a
 * `@Component`. [NavMenuAggregator] injects them all, evaluates them per request, and
 * produces the ordered group/item model the shell template iterates.
 *
 * Visibility lives in the contributor, not the template: a contributor emits an item only
 * when it should be visible for the current request, deciding from [UiRequestContext.hasPermission]
 * and/or [UiRequestContext.isFeatureEnabled] (both resolved once per render by the host).
 * A group is shown only when it has at least one visible item.
 */
interface NavContributor {
    /**
     * Group declarations this contributor owns (identity + label + ordering). Usually empty
     * (the contributor only adds items to a group declared elsewhere) or a single group.
     * Exactly one contributor should declare a given [NavGroup.key].
     */
    fun groups(context: UiRequestContext): List<NavGroup> = emptyList()

    /** Items to show for this request, already filtered by toggle/permission. */
    fun items(context: UiRequestContext): List<NavItem>
}

/** A dropdown group: stable identity ([key]) plus display [label] and [order]. */
data class NavGroup(
    val key: String,
    val label: String,
    val order: Int,
    /** Optional `data-testid` for the dropdown trigger, e.g. "nav-dropdown-support". */
    val testId: String? = null,
)

/** A single nav entry contributed under a group. */
data class NavItem(
    /** Key of the [NavGroup] this item belongs to. */
    val groupKey: String,
    /** Unique active-highlight key, e.g. "feedback". Also used to build the item `data-testid`. */
    val sectionKey: String,
    val label: String,
    /** Path under `/tenants/{id}/`, e.g. "feedback" or "code-lists". */
    val pathSuffix: String,
    /** Order within the group (ascending; ties broken by [sectionKey]). */
    val order: Int,
)

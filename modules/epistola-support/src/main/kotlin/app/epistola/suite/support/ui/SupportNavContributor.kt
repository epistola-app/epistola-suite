// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.ui

import app.epistola.suite.htmx.UiRequestContext
import app.epistola.suite.htmx.nav.NavContributor
import app.epistola.suite.htmx.nav.NavGroup
import app.epistola.suite.htmx.nav.NavItem
import app.epistola.suite.security.Permission
import app.epistola.suite.support.SupportProperties
import org.springframework.stereotype.Component

/**
 * Owns the "Support" nav group ([GROUP_KEY]) and its Overview item.
 *
 * Other modules add their own items to the group with `NavItem(SupportNavContributor.GROUP_KEY, …)`:
 * feedback for everyone who may use it, the per-tenant support settings for tenant administrators.
 * The group is dropped when it has no items. Overview is for tenant administrators while the
 * support connection is on: with it off there is nothing to say there.
 */
@Component
class SupportNavContributor(
    private val properties: SupportProperties,
) : NavContributor {

    override fun groups(context: UiRequestContext): List<NavGroup> = listOf(
        NavGroup(key = GROUP_KEY, label = "Support", order = 80, testId = "nav-dropdown-support"),
    )

    override fun items(context: UiRequestContext): List<NavItem> {
        if (!properties.enabled || !context.hasPermission(Permission.TENANT_SETTINGS)) return emptyList()
        return listOf(NavItem(GROUP_KEY, OVERVIEW_SECTION, "Overview", "support", order = 0))
    }

    companion object {
        /** The Support group's key: contribute items with `NavItem(GROUP_KEY, …)`. */
        const val GROUP_KEY = "support"

        /** The Overview item's section key; the tenant support page lists the group's other items. */
        const val OVERVIEW_SECTION = "overview"
    }
}

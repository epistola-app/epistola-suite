// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.audit.ui

import app.epistola.suite.htmx.UiRequestContext
import app.epistola.suite.htmx.nav.NavContributor
import app.epistola.suite.htmx.nav.NavItem
import app.epistola.suite.security.Permission
import org.springframework.stereotype.Component

/**
 * Contributes the **Audit** item to the host's Operations group, for users with
 * the `AUDIT_VIEW` permission. The Operations group is declared by the host's
 * core nav contributor; the aggregator merges this item into it (and drops the
 * group if no item is visible).
 */
@Component
class AuditNavContributor : NavContributor {
    override fun items(context: UiRequestContext): List<NavItem> {
        if (!context.hasPermission(Permission.AUDIT_VIEW)) return emptyList()
        return listOf(NavItem("operations", "audit", "Audit", "audit", 47))
    }
}

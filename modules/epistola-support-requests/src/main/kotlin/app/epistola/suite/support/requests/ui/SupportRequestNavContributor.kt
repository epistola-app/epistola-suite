// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.ui

import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveAvailableFeatures
import app.epistola.suite.htmx.UiRequestContext
import app.epistola.suite.htmx.nav.NavContributor
import app.epistola.suite.htmx.nav.NavItem
import app.epistola.suite.mediator.query
import app.epistola.suite.support.ui.SupportNavContributor
import org.springframework.stereotype.Component

/**
 * Adds to the Support nav group:
 * - Support requests, when the `support-requests` feature toggle is on. The toggle only shows or hides
 *   them; it is not consent to send anything.
 *
 * What a tenant shares, support requests included, is set on its Support page.
 */
@Component
class SupportRequestNavContributor : NavContributor {
    override fun items(context: UiRequestContext): List<NavItem> {
        val available = ResolveAvailableFeatures(context.tenantKey).query()
        return buildList {
            if (available[KnownFeatures.SUPPORT_REQUESTS] == true) {
                add(NavItem(SupportNavContributor.GROUP_KEY, "requests", "Support requests", "support/requests", order = 10))
            }
        }
    }
}

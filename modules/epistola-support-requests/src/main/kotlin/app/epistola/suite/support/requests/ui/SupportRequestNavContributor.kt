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
import app.epistola.suite.security.Permission
import app.epistola.suite.support.SupportProperties
import app.epistola.suite.support.ui.SupportNavContributor
import org.springframework.stereotype.Component

/**
 * Adds to the Support nav group:
 * - Feedback, when the `support-requests` feature toggle is on. The toggle only shows or hides the
 *   feedback UI; it is not consent to send anything.
 * - Feedback sharing (`/tenants/{t}/support/request-sharing`), for those who manage the tenant's
 *   integrations, while the support connection is on: there the tenant consents to sending its
 *   feedback to Epistola support.
 */
@Component
class SupportRequestNavContributor(
    private val supportProperties: SupportProperties,
) : NavContributor {
    override fun items(context: UiRequestContext): List<NavItem> {
        val available = ResolveAvailableFeatures(context.tenantKey).query()
        return buildList {
            if (available[KnownFeatures.SUPPORT_REQUESTS] == true) {
                add(NavItem(SupportNavContributor.GROUP_KEY, "requests", "Support requests", "support/requests", order = 10))
            }
            if (supportProperties.enabled && context.hasPermission(Permission.INTEGRATIONS_MANAGE)) {
                add(NavItem(SupportNavContributor.GROUP_KEY, SHARING_SECTION, "Support request sharing", "support/request-sharing", order = 20, stage = KnownFeatures.FeatureStage.ALPHA))
            }
        }
    }

    companion object {
        /** The Feedback sharing item's section key. */
        const val SHARING_SECTION = "support-request-sharing"
    }
}

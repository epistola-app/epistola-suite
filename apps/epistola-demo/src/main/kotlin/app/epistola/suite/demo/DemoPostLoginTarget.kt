// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PostLoginTargetResolver
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Demo-only: lands a signed-in visitor in their own tenant instead of the tenant picker.
 *
 * The picker at `/` is right for a customer working across several tenants. A demo visitor has one
 * that is theirs, so picking is a click between them and the product.
 *
 * Deliberately a post-login target rather than a redirect on `GET /`. The tenant list **is** `/` —
 * there is no `GET /tenants` — and "Switch tenant" in the nav, "Back to tenants" in the platform
 * banner and the error pages' "Back to Home" all point there. Redirecting the route would make the
 * switcher a no-op and put the shared `demo` tenant out of reach through the UI, which is precisely
 * where [DemoLoginMembershipResolver] has just granted everyone read/write. Landing is a
 * one-time-per-login decision; navigation stays navigation.
 */
@Component
@ConditionalOnProperty(name = ["epistola.demo.enabled"], havingValue = "true")
class DemoPostLoginTarget : PostLoginTargetResolver {

    override fun resolve(principal: EpistolaPrincipal): String? {
        val personal = DemoLoginMembershipResolver.deriveTenantKeyFromEmail(principal.email) ?: return null

        // Only somewhere they can actually get into. A principal whose memberships came from the
        // identity provider rather than from the demo resolver has no personal tenant, and one whose
        // tenant creation failed at login would land on a 404.
        if (!principal.tenantMemberships.containsKey(personal)) return null

        return "/tenants/${personal.value}"
    }
}

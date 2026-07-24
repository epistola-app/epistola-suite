// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.htmx.page
import app.epistola.suite.security.AuthProperties
import app.epistola.suite.security.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Profile — the signed-in user's own account page.
 *
 * Shows the user's identity and the authorization the app resolved for them
 * (tenant memberships, global roles, platform roles, from the [EpistolaPrincipal]),
 * plus a debug section listing a curated set of decoded OIDC id-token claim values.
 *
 * Scope guard (issue #578): the raw token is **never** rendered or logged — only the
 * curated claim values produced by [ProfileTokenClaims]. Under plain form login there is
 * no token, so the claims section degrades gracefully (`hasToken = false`).
 *
 * This is a UI-only, per-user view (`GET /profile`); it is deliberately not exposed on
 * the REST API or MCP server.
 */
@Component
class ProfileHandler(
    private val authProperties: AuthProperties,
) {
    fun profile(request: ServerRequest): ServerResponse {
        val principal = SecurityContext.current()

        val memberships = principal.tenantMemberships
            .map { (tenant, roles) -> TenantMembershipView(tenant.value, roles.map { it.name }.sorted()) }
            .sortedBy { it.tenant }

        // Reach the raw OidcUser only to read curated id-token claim values. Absent under form login.
        val oidcUser = SecurityContextHolder.getContext()?.authentication?.principal as? OidcUser
        val claimRows = oidcUser
            ?.let { ProfileTokenClaims.extract(it.idToken.claims, authProperties.flatRoles.claimName) }
            ?: emptyList()

        return ServerResponse.ok().page("profile") {
            "pageTitle" to "Profile - Epistola"
            "activeNavSection" to "profile"
            // Resolve a tenant for the shell chrome (logo / nav). Null for users with no membership;
            // the shell interceptor degrades gracefully when tenantId is absent.
            "tenantId" to principal.effectiveTenantId()
            "displayName" to principal.displayName
            "email" to principal.email
            "externalId" to principal.externalId
            "memberships" to memberships
            "globalRoles" to principal.globalRoles.map { it.name }.sorted()
            "platformRoles" to principal.platformRoles.map { it.name }.sorted()
            "hasToken" to (oidcUser != null)
            "claimRows" to claimRows
        }
    }

    /** A tenant the user belongs to and the roles they hold there, for the authorization table. */
    data class TenantMembershipView(val tenant: String, val roles: List<String>)
}

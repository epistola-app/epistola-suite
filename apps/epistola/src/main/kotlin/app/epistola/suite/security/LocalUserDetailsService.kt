// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.security

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.query
import app.epistola.suite.users.AuthProvider
import app.epistola.suite.users.commands.EnsureUser
import app.epistola.suite.users.queries.GetUserByExternalId
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * In-memory user details service for local development and non-production environments.
 *
 * Users are configured via `epistola.auth.local-users` properties, allowing credentials
 * and roles to be overridden via environment variables for staging/test environments.
 *
 * Active when 'local' or 'localauth' profile is active.
 */
@Component
@Profile("local | localauth")
class LocalUserDetailsService(
    authProperties: AuthProperties,
    private val mediator: Mediator,
    /** Present under the `demo` profile; absent otherwise, which is what makes `sandbox` optional. */
    private val membershipResolver: LoginMembershipResolver? = null,
) : UserDetailsService {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    private val localUsers: Map<String, LocalUserProperties> =
        authProperties.localUsers.associateBy { it.username }

    override fun loadUserByUsername(username: String): UserDetails {
        val localUser = localUsers[username]
            ?: throw UsernameNotFoundException("User not found: $username")

        val userId = UserKey.of(deterministicUuid(localUser.username))

        // Local users are authenticated from configuration, not provisioned via
        // OAuth2UserProvisioningService. Audit columns (created_by / updated_by)
        // are real FKs to users(id), so the row must exist before this principal
        // performs any write. Idempotent on the deterministic id (stable across
        // restarts), so repeated logins are a no-op.
        mediator.send(
            EnsureUser(
                id = userId,
                externalId = localUser.username,
                email = localUser.username,
                displayName = localUser.displayName,
                provider = AuthProvider.LOCAL,
            ),
        )

        val principal = sandboxPrincipal(localUser, userId) ?: EpistolaPrincipal(
            userId = userId,
            externalId = localUser.username,
            email = localUser.username,
            displayName = localUser.displayName,
            tenantMemberships = mapOf(
                TenantKey.of(localUser.tenant) to localUser.roles,
            ),
            globalRoles = localUser.roles,
            platformRoles = localUser.platformRoles,
            currentTenantId = TenantKey.of(localUser.tenant),
        )

        return LocalUserDetails(
            username = localUser.username,
            password = passwordEncoder.encode(localUser.password)
                ?: throw IllegalStateException("Password encoding failed"),
            epistolaPrincipal = principal,
        )
    }

    /**
     * The principal for a `sandbox: true` user, or null to fall back to the configured tenant.
     *
     * The resolver both derives the tenant and creates it, so this is also what makes a sandbox
     * exist at all. Its memberships replace the configured ones outright rather than merging: the
     * point of a sandbox is that it is the user's own, and a leftover membership of a shared tenant
     * would quietly undo that.
     */
    private fun sandboxPrincipal(localUser: LocalUserProperties, userId: UserKey): EpistolaPrincipal? {
        if (!localUser.sandbox) return null
        if (membershipResolver == null) {
            logger.warn(
                "Local user {} asks for a sandbox tenant but no LoginMembershipResolver is present " +
                    "(the demo profile supplies one); falling back to the configured tenant '{}'.",
                localUser.username,
                localUser.tenant,
            )
            return null
        }

        // EnsureUser has just run, so the row exists; the resolver needs the domain object to
        // persist the memberships it derives.
        val user = mediator.query(GetUserByExternalId(localUser.username, AuthProvider.LOCAL)) ?: return null
        val resolved = membershipResolver.resolve(localUser.username, user) ?: return null
        val sandboxTenant = resolved.tenantMemberships.keys.firstOrNull() ?: return null

        return EpistolaPrincipal(
            userId = userId,
            externalId = localUser.username,
            email = localUser.username,
            displayName = localUser.displayName,
            tenantMemberships = resolved.tenantMemberships,
            globalRoles = resolved.globalRoles,
            platformRoles = resolved.platformRoles + localUser.platformRoles,
            currentTenantId = sandboxTenant,
        )
    }

    /**
     * UserDetails implementation that holds EpistolaPrincipal.
     * Implements Serializable for Spring Session JDBC support.
     */
    private class LocalUserDetails(
        private val username: String,
        private val password: String,
        override val epistolaPrincipal: EpistolaPrincipal,
    ) : UserDetails,
        EpistolaPrincipalHolder,
        java.io.Serializable {
        override fun getUsername() = username
        override fun getPassword() = password
        override fun getAuthorities() = emptyList<org.springframework.security.core.GrantedAuthority>()
        override fun isEnabled() = true
        override fun isCredentialsNonExpired() = true
        override fun isAccountNonExpired() = true
        override fun isAccountNonLocked() = true

        companion object {
            private const val serialVersionUID: Long = 4L
        }
    }

    companion object {
        /**
         * Generates a deterministic UUID from a username so user IDs are stable across restarts.
         */
        private fun deterministicUuid(username: String): UUID = UUID.nameUUIDFromBytes(username.toByteArray(StandardCharsets.UTF_8))
    }
}

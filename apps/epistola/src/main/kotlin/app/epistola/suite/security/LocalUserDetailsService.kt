package app.epistola.suite.security

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
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
) : UserDetailsService {

    private val passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    private val localUsers: Map<String, LocalUserProperties> =
        authProperties.localUsers.associateBy { it.username }

    override fun loadUserByUsername(username: String): UserDetails {
        val localUser = localUsers[username]
            ?: throw UsernameNotFoundException("User not found: $username")

        val userId = UserKey.of(deterministicUuid(localUser.username))

        val principal = EpistolaPrincipal(
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

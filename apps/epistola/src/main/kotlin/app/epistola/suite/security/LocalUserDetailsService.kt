package app.epistola.suite.security

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import org.springframework.context.annotation.Profile
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.stereotype.Component

/**
 * In-memory user details service for local development and demo environments.
 *
 * Provides hardcoded test users without requiring external OAuth2 providers or database setup.
 *
 * Users:
 * - admin@local / admin - Admin user with access to demo-tenant (ADMIN role)
 * - user@local / user - Regular user with access to demo-tenant (MEMBER role)
 *
 * Active when 'local' or 'demo' profile is active.
 */
@Component
@Profile("local | demo")
class LocalUserDetailsService : UserDetailsService {

    private val passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    // In-memory users for local development
    private val localUsers = mapOf(
        "admin@local" to LocalUser(
            userId = UserKey.of("00000000-0000-0000-0000-000000000001"),
            email = "admin@local",
            displayName = "Local Admin",
            password = "admin",
            tenantMemberships = mapOf(TenantKey.of("demo-tenant") to TenantRole.ADMIN),
        ),
        "user@local" to LocalUser(
            userId = UserKey.of("00000000-0000-0000-0000-000000000002"),
            email = "user@local",
            displayName = "Local User",
            password = "user",
            tenantMemberships = mapOf(TenantKey.of("demo-tenant") to TenantRole.MEMBER),
        ),
    )

    override fun loadUserByUsername(username: String): UserDetails {
        val localUser = localUsers[username]
            ?: throw UsernameNotFoundException("User not found: $username")

        val principal = EpistolaPrincipal(
            userId = localUser.userId,
            externalId = localUser.email,
            email = localUser.email,
            displayName = localUser.displayName,
            tenantMemberships = localUser.tenantMemberships,
            currentTenantId = localUser.tenantMemberships.keys.firstOrNull(),
        )

        return LocalUserDetails(
            username = localUser.email,
            password = passwordEncoder.encode(localUser.password) ?: throw IllegalStateException("Password encoding failed"),
            epistolaPrincipal = principal,
        )
    }

    /**
     * Local user configuration.
     */
    private data class LocalUser(
        val userId: UserKey,
        val email: String,
        val displayName: String,
        val password: String,
        val tenantMemberships: Map<TenantKey, TenantRole>,
    )

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
            private const val serialVersionUID: Long = 2L
        }
    }
}

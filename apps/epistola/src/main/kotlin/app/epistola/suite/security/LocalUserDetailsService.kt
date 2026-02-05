package app.epistola.suite.security

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserId
import org.springframework.context.annotation.Profile
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.stereotype.Component

/**
 * In-memory user details service for local development.
 *
 * Provides hardcoded test users for local development without requiring
 * external OAuth2 providers or database setup.
 *
 * Users:
 * - admin@local / admin - Admin user with access to all tenants
 * - user@local / user - Regular user with access to demo-tenant
 *
 * Active only when 'local' profile is active.
 */
@Component
@Profile("local")
class LocalUserDetailsService : UserDetailsService {

    private val passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    // In-memory users for local development
    private val localUsers = mapOf(
        "admin@local" to LocalUser(
            userId = UserId.of("00000000-0000-0000-0000-000000000001"),
            email = "admin@local",
            displayName = "Local Admin",
            password = "admin",
            tenantIds = emptySet(), // Admin has access to all tenants
        ),
        "user@local" to LocalUser(
            userId = UserId.of("00000000-0000-0000-0000-000000000002"),
            email = "user@local",
            displayName = "Local User",
            password = "user",
            tenantIds = setOf(TenantId.of("demo-tenant")),
        ),
    )

    override fun loadUserByUsername(username: String): UserDetails {
        val localUser = localUsers[username]
            ?: throw UsernameNotFoundException("User not found: $username")

        // Create EpistolaPrincipal for the local user
        val principal = app.epistola.suite.security.EpistolaPrincipal(
            userId = localUser.userId,
            externalId = localUser.email,
            email = localUser.email,
            displayName = localUser.displayName,
            tenantMemberships = localUser.tenantIds,
            currentTenantId = localUser.tenantIds.firstOrNull(),
        )

        // Return Spring Security UserDetails with EpistolaPrincipal as principal
        return LocalUserDetails(
            username = localUser.email,
            password = passwordEncoder.encode(localUser.password) ?: throw IllegalStateException("Password encoding failed"),
            principal = principal,
        )
    }

    /**
     * Local user configuration.
     */
    private data class LocalUser(
        val userId: UserId,
        val email: String,
        val displayName: String,
        val password: String,
        val tenantIds: Set<TenantId>,
    )

    /**
     * UserDetails implementation that holds EpistolaPrincipal.
     * Implements Serializable for Spring Session JDBC support.
     */
    private class LocalUserDetails(
        private val username: String,
        private val password: String,
        val principal: app.epistola.suite.security.EpistolaPrincipal,
    ) : UserDetails, java.io.Serializable {
        override fun getUsername() = username
        override fun getPassword() = password
        override fun getAuthorities() = emptyList<org.springframework.security.core.GrantedAuthority>()
        override fun isEnabled() = true
        override fun isCredentialsNonExpired() = true
        override fun isAccountNonExpired() = true
        override fun isAccountNonLocked() = true

        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}

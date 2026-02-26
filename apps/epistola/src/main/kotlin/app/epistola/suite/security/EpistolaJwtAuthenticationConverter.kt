package app.epistola.suite.security

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

/**
 * Converts a JWT into an authentication token with an [EpistolaPrincipal].
 *
 * Extracts user identity from standard JWT claims:
 * - `sub`: user external ID
 * - `email`: user email
 * - `preferred_username` or `name`: display name
 * - `epistola_tenants`: optional array of tenant IDs the user has access to
 *
 * Only active when an OAuth2 ClientRegistrationRepository is present.
 */
@Component
@ConditionalOnBean(ClientRegistrationRepository::class)
class EpistolaJwtAuthenticationConverter : Converter<Jwt, AbstractAuthenticationToken> {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        val subject = jwt.subject ?: throw IllegalArgumentException("JWT missing 'sub' claim")
        val email = jwt.getClaimAsString("email") ?: "$subject@unknown"
        val displayName = jwt.getClaimAsString("preferred_username")
            ?: jwt.getClaimAsString("name")
            ?: subject

        // Extract tenant memberships from custom claim or default to empty
        val tenantIds = extractTenantIds(jwt)

        val principal = EpistolaPrincipal(
            userId = UserKey.of(deriveUserId(subject)),
            externalId = subject,
            email = email,
            displayName = displayName,
            tenantMemberships = tenantIds,
            currentTenantId = tenantIds.firstOrNull(),
        )

        val authorities = jwt.getClaimAsStringList("roles")
            ?.map { SimpleGrantedAuthority("ROLE_$it") }
            ?: listOf(SimpleGrantedAuthority("ROLE_API_USER"))

        return JwtAuthenticationToken(jwt, authorities, subject).apply {
            details = principal
        }
    }

    /**
     * Extracts tenant IDs from the `epistola_tenants` JWT claim.
     * Falls back to empty set if the claim is not present.
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractTenantIds(jwt: Jwt): Set<TenantKey> {
        val tenants = jwt.getClaimAsStringList("epistola_tenants") ?: return emptySet()
        return tenants.mapNotNull { tenantString ->
            try {
                TenantKey.of(tenantString)
            } catch (e: IllegalArgumentException) {
                log.warn("Invalid tenant ID in JWT claim: {}", tenantString)
                null
            }
        }.toSet()
    }

    /**
     * Derives a deterministic UUID from the JWT subject claim.
     * Uses UUID v5 (name-based with SHA-1) with the URL namespace.
     */
    private fun deriveUserId(subject: String): java.util.UUID = java.util.UUID.nameUUIDFromBytes(subject.toByteArray())
}

package app.epistola.suite.security

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
 * - `groups`: Keycloak group memberships (parsed via [parseGroupMemberships])
 *
 * The `groups` claim contains hierarchical Keycloak group paths:
 * - `/epistola/tenants/{tenant}/{role}` → per-tenant role (e.g., `/epistola/tenants/acme-corp/reader`)
 * - `/epistola/global/{role}` → global role for all tenants (e.g., `/epistola/global/reader`)
 * - `/epistola/platform/{role}` → platform role (e.g., `/epistola/platform/tenant-manager`)
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

        val groups = jwt.getClaimAsStringList("groups") ?: emptyList()
        val parsed = parseGroupMemberships(groups)

        val principal = EpistolaPrincipal(
            userId = UserKey.of(deriveUserId(subject)),
            externalId = subject,
            email = email,
            displayName = displayName,
            tenantMemberships = parsed.tenantRoles,
            globalRoles = parsed.globalRoles,
            platformRoles = parsed.platformRoles,
            currentTenantId = parsed.tenantRoles.keys.firstOrNull(),
        )

        val authorities = buildList {
            jwt.getClaimAsStringList("roles")
                ?.forEach { add(SimpleGrantedAuthority("ROLE_$it")) }
                ?: add(SimpleGrantedAuthority("ROLE_API_USER"))

            parsed.platformRoles.forEach { add(SimpleGrantedAuthority("ROLE_${it.name}")) }
        }

        return JwtAuthenticationToken(jwt, authorities, subject).apply {
            details = principal
        }
    }

    /**
     * Derives a deterministic UUID from the JWT subject claim.
     * Uses UUID v5 (name-based with SHA-1) with the URL namespace.
     */
    private fun deriveUserId(subject: String): java.util.UUID = java.util.UUID.nameUUIDFromBytes(subject.toByteArray())
}

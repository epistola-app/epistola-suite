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
 * - `epistola_tenants`: tenant memberships with roles
 *
 * The `epistola_tenants` claim supports two formats:
 * - **Structured** (preferred): `[{"id": "acme", "role": "ADMIN"}, {"id": "beta", "role": "MEMBER"}]`
 * - **Legacy** (flat list): `["acme", "beta"]` — all users default to MEMBER role
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

        val tenantMemberships = extractTenantMemberships(jwt)

        val principal = EpistolaPrincipal(
            userId = UserKey.of(deriveUserId(subject)),
            externalId = subject,
            email = email,
            displayName = displayName,
            tenantMemberships = tenantMemberships,
            currentTenantId = tenantMemberships.keys.firstOrNull(),
        )

        val authorities = jwt.getClaimAsStringList("roles")
            ?.map { SimpleGrantedAuthority("ROLE_$it") }
            ?: listOf(SimpleGrantedAuthority("ROLE_API_USER"))

        return JwtAuthenticationToken(jwt, authorities, subject).apply {
            details = principal
        }
    }

    /**
     * Extracts tenant memberships from the `epistola_tenants` JWT claim.
     *
     * Supports structured format `[{"id": "acme", "role": "ADMIN"}]` and
     * falls back to legacy flat list `["acme"]` (defaulting to MEMBER role).
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractTenantMemberships(jwt: Jwt): Map<TenantKey, TenantRole> {
        val claim = jwt.getClaim<Any>("epistola_tenants") ?: return emptyMap()

        return when (claim) {
            is List<*> -> parseClaimList(claim)
            else -> {
                log.warn("Unexpected epistola_tenants claim type: {}", claim::class.simpleName)
                emptyMap()
            }
        }
    }

    private fun parseClaimList(items: List<*>): Map<TenantKey, TenantRole> {
        val result = mutableMapOf<TenantKey, TenantRole>()

        for (item in items) {
            when (item) {
                // Structured format: {"id": "acme", "role": "ADMIN"}
                is Map<*, *> -> {
                    val id = item["id"]?.toString()
                    val role = item["role"]?.toString()
                    if (id != null) {
                        try {
                            val tenantKey = TenantKey.of(id)
                            val tenantRole = role?.let { parseTenantRole(it) } ?: TenantRole.MEMBER
                            result[tenantKey] = tenantRole
                        } catch (e: IllegalArgumentException) {
                            log.warn("Invalid tenant ID in JWT claim: {}", id)
                        }
                    }
                }
                // Legacy format: plain string tenant ID
                is String -> {
                    try {
                        result[TenantKey.of(item)] = TenantRole.MEMBER
                    } catch (e: IllegalArgumentException) {
                        log.warn("Invalid tenant ID in JWT claim: {}", item)
                    }
                }
                else -> log.warn("Unexpected item type in epistola_tenants: {}", item?.javaClass?.simpleName)
            }
        }

        return result
    }

    private fun parseTenantRole(role: String): TenantRole = try {
        TenantRole.valueOf(role.uppercase())
    } catch (e: IllegalArgumentException) {
        log.warn("Unknown tenant role in JWT claim: {}, defaulting to MEMBER", role)
        TenantRole.MEMBER
    }

    /**
     * Derives a deterministic UUID from the JWT subject claim.
     * Uses UUID v5 (name-based with SHA-1) with the URL namespace.
     */
    private fun deriveUserId(subject: String): java.util.UUID = java.util.UUID.nameUUIDFromBytes(subject.toByteArray())
}

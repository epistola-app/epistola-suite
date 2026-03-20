package app.epistola.suite.security

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.security.PlatformRole
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
 * - `epistola_tenants`: tenant memberships with composable roles
 * - `resource_access.epistola-suite.roles`: platform roles
 *
 * The `epistola_tenants` claim format:
 * ```json
 * [{"id": "acme", "roles": ["reader", "editor"]}, {"id": "beta", "roles": ["reader"]}]
 * ```
 *
 * Legacy formats are also supported:
 * - Single role: `[{"id": "acme", "role": "ADMIN"}]` → mapped to equivalent role set
 * - Flat list: `["acme", "beta"]` → defaults to `[READER]`
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
        val platformRoles = extractPlatformRoles(jwt)

        val principal = EpistolaPrincipal(
            userId = UserKey.of(deriveUserId(subject)),
            externalId = subject,
            email = email,
            displayName = displayName,
            tenantMemberships = tenantMemberships,
            platformRoles = platformRoles,
            currentTenantId = tenantMemberships.keys.firstOrNull(),
        )

        val authorities = buildList {
            // Add roles from JWT
            jwt.getClaimAsStringList("roles")
                ?.forEach { add(SimpleGrantedAuthority("ROLE_$it")) }
                ?: add(SimpleGrantedAuthority("ROLE_API_USER"))

            // Add platform roles as Spring Security authorities
            platformRoles.forEach { add(SimpleGrantedAuthority("ROLE_${it.name}")) }
        }

        return JwtAuthenticationToken(jwt, authorities, subject).apply {
            details = principal
        }
    }

    /**
     * Extracts tenant memberships from the `epistola_tenants` JWT claim.
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractTenantMemberships(jwt: Jwt): Map<TenantKey, Set<TenantRole>> {
        val claim = jwt.getClaim<Any>("epistola_tenants") ?: return emptyMap()

        return when (claim) {
            is List<*> -> parseClaimList(claim)
            else -> {
                log.warn("Unexpected epistola_tenants claim type: {}", claim::class.simpleName)
                emptyMap()
            }
        }
    }

    private fun parseClaimList(items: List<*>): Map<TenantKey, Set<TenantRole>> {
        val result = mutableMapOf<TenantKey, Set<TenantRole>>()

        for (item in items) {
            when (item) {
                is Map<*, *> -> {
                    val id = item["id"]?.toString() ?: continue
                    val tenantKey = try {
                        TenantKey.of(id)
                    } catch (e: IllegalArgumentException) {
                        log.warn("Invalid tenant ID in JWT claim: {}", id)
                        continue
                    }

                    // Preferred: "roles" array
                    val rolesArray = item["roles"]
                    // Legacy: single "role" string
                    val singleRole = item["role"]?.toString()

                    val roles = when {
                        rolesArray is List<*> -> rolesArray.mapNotNull { parseTenantRole(it?.toString()) }.toSet()
                        singleRole != null -> parseTenantRole(singleRole)?.let { setOf(it) } ?: setOf(TenantRole.READER)
                        else -> setOf(TenantRole.READER)
                    }

                    if (roles.isNotEmpty()) {
                        result[tenantKey] = roles
                    }
                }
                // Legacy flat list format: plain string tenant ID
                is String -> {
                    try {
                        result[TenantKey.of(item)] = setOf(TenantRole.READER)
                    } catch (e: IllegalArgumentException) {
                        log.warn("Invalid tenant ID in JWT claim: {}", item)
                    }
                }
                else -> log.warn("Unexpected item type in epistola_tenants: {}", item?.javaClass?.simpleName)
            }
        }

        return result
    }

    private fun parseTenantRole(role: String?): TenantRole? {
        if (role == null) return null
        return try {
            TenantRole.valueOf(role.uppercase())
        } catch (e: IllegalArgumentException) {
            log.warn("Unknown tenant role in JWT claim: {}", role)
            null
        }
    }

    /**
     * Extracts platform roles from the `resource_access.epistola-suite.roles` JWT claim.
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractPlatformRoles(jwt: Jwt): Set<PlatformRole> {
        val resourceAccess = jwt.getClaim<Map<String, Any>>("resource_access") ?: return emptySet()
        val clientAccess = resourceAccess[CLIENT_ID] as? Map<String, Any> ?: return emptySet()
        val roles = clientAccess["roles"] as? List<*> ?: return emptySet()

        return roles.mapNotNull { roleName ->
            when (roleName?.toString()) {
                "tenant-manager" -> PlatformRole.TENANT_MANAGER
                else -> {
                    log.warn("Unknown platform role in JWT: {}", roleName)
                    null
                }
            }
        }.toSet()
    }

    /**
     * Derives a deterministic UUID from the JWT subject claim.
     * Uses UUID v5 (name-based with SHA-1) with the URL namespace.
     */
    private fun deriveUserId(subject: String): java.util.UUID = java.util.UUID.nameUUIDFromBytes(subject.toByteArray())

    companion object {
        /** The Keycloak client ID used to scope platform roles in `resource_access`. */
        const val CLIENT_ID = "epistola-suite"
    }
}

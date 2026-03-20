package app.epistola.suite.security

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.users.AuthProvider
import app.epistola.suite.users.User
import app.epistola.suite.users.commands.CreateUser
import app.epistola.suite.users.commands.UpdateLastLogin
import app.epistola.suite.users.queries.GetUserByExternalId
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Component

/**
 * OAuth2 user service that auto-provisions users on first login.
 *
 * This service:
 * 1. Loads user info from OAuth2 provider (Keycloak, etc.)
 * 2. Checks if user exists in database
 * 3. Auto-provisions user if enabled and not found
 * 4. Updates last login timestamp
 * 5. Converts to EpistolaPrincipal for use by business logic
 *
 * The [AuthProvider] is derived from the OAuth2 registration ID:
 * - `"keycloak"` → [AuthProvider.KEYCLOAK]
 * - anything else → [AuthProvider.GENERIC_OIDC]
 *
 * Only loaded when a [ClientRegistrationRepository] bean is present (i.e., OAuth2 is configured).
 */
@Component
@ConditionalOnBean(ClientRegistrationRepository::class)
class OAuth2UserProvisioningService(
    private val mediator: Mediator,
    private val authProperties: AuthProperties,
) : OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private val delegate = DefaultOAuth2UserService()
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun loadUser(request: OAuth2UserRequest): OAuth2User {
        // Load user info from OAuth2 provider
        val oauth2User = delegate.loadUser(request)

        // Derive AuthProvider from the OAuth2 registration ID
        val provider = deriveAuthProvider(request.clientRegistration.registrationId)

        // Extract required claims
        val externalId = oauth2User.getAttribute<String>("sub")
            ?: throw OAuth2AuthenticationException(
                OAuth2Error("missing_sub_claim"),
                "Missing 'sub' claim in OAuth2 user info",
            )
        val email = oauth2User.getAttribute<String>("email")
            ?: throw OAuth2AuthenticationException(
                OAuth2Error("missing_email_claim"),
                "Missing 'email' claim in OAuth2 user info",
            )
        val displayName = oauth2User.getAttribute<String>("name") ?: email

        // Get or create user in database
        val user = try {
            getOrCreateUser(
                externalId = externalId,
                email = email,
                displayName = displayName,
                provider = provider,
            )
        } catch (e: Exception) {
            logger.error("Failed to provision user: $email", e)
            throw OAuth2AuthenticationException(
                OAuth2Error("user_provisioning_failed"),
                "Failed to provision user",
                e,
            )
        }

        // Update last login timestamp
        updateLastLogin(user.id)

        // Extract roles from OIDC token attributes (overrides DB-level memberships)
        val tokenMemberships = extractTenantMemberships(oauth2User)

        // Merge: token roles take precedence over DB-loaded roles
        val mergedMemberships = if (tokenMemberships.isNotEmpty()) {
            tokenMemberships
        } else {
            user.tenantMemberships
        }

        // Extract platform roles from OIDC token
        val platformRoles = extractPlatformRoles(oauth2User)

        // Convert to EpistolaPrincipal with merged memberships and platform roles
        val principal = user.toEpistolaPrincipal(mergedMemberships, platformRoles)

        // Return OAuth2User wrapper that Spring Security can use
        return OAuth2UserWrapper(oauth2User, principal)
    }

    private fun getOrCreateUser(
        externalId: String,
        email: String,
        displayName: String,
        provider: AuthProvider,
    ): User {
        // Try to find existing user
        val existing = mediator.query(GetUserByExternalId(externalId, provider))

        if (existing != null) {
            if (!existing.enabled) {
                throw OAuth2AuthenticationException(
                    OAuth2Error("user_disabled"),
                    "User account is disabled",
                )
            }
            return existing
        }

        // Auto-provision if enabled
        if (!authProperties.autoProvision) {
            throw OAuth2AuthenticationException(
                OAuth2Error("auto_provision_disabled"),
                "User not found and auto-provisioning is disabled",
            )
        }

        // Create new user
        logger.info("Auto-provisioning new user: $email (provider: $provider)")

        return mediator.send(
            CreateUser(
                externalId = externalId,
                email = email,
                displayName = displayName,
                provider = provider,
            ),
        )
    }

    private fun updateLastLogin(userId: app.epistola.suite.common.ids.UserKey) {
        mediator.send(UpdateLastLogin(userId))
    }

    /**
     * Wrapper that combines OAuth2User (for Spring Security) with EpistolaPrincipal.
     * Implements Serializable for JDBC session persistence.
     */
    private class OAuth2UserWrapper(
        private val delegate: OAuth2User,
        override val epistolaPrincipal: app.epistola.suite.security.EpistolaPrincipal,
    ) : OAuth2User by delegate,
        EpistolaPrincipalHolder,
        java.io.Serializable {

        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    /**
     * Extracts tenant memberships with composable roles from the OAuth2 user's `epistola_tenants` attribute.
     *
     * Preferred format: `[{"id": "acme", "roles": ["reader", "editor"]}]`
     * Legacy formats: `[{"id": "acme", "role": "ADMIN"}]` or `["acme"]`
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractTenantMemberships(oauth2User: OAuth2User): Map<TenantKey, Set<TenantRole>> {
        val claim = oauth2User.getAttribute<Any>("epistola_tenants") ?: return emptyMap()

        if (claim !is List<*>) {
            logger.warn("Unexpected epistola_tenants attribute type: {}", claim::class.simpleName)
            return emptyMap()
        }

        val result = mutableMapOf<TenantKey, Set<TenantRole>>()
        for (item in claim) {
            when (item) {
                is Map<*, *> -> {
                    val id = item["id"]?.toString() ?: continue
                    val tenantKey = try {
                        TenantKey.of(id)
                    } catch (e: IllegalArgumentException) {
                        logger.warn("Invalid tenant ID in OIDC claim: {}", id)
                        continue
                    }

                    val rolesArray = item["roles"]
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
                is String -> {
                    try {
                        result[TenantKey.of(item)] = setOf(TenantRole.READER)
                    } catch (e: IllegalArgumentException) {
                        logger.warn("Invalid tenant ID in OIDC claim: {}", item)
                    }
                }
            }
        }
        return result
    }

    private fun parseTenantRole(role: String?): TenantRole? {
        if (role == null) return null
        return try {
            TenantRole.valueOf(role.uppercase())
        } catch (e: IllegalArgumentException) {
            logger.warn("Unknown tenant role in OIDC claim: {}", role)
            null
        }
    }

    /**
     * Extracts platform roles from the OAuth2 user's attributes.
     *
     * Keycloak includes client roles in the `resource_access` attribute:
     * ```json
     * { "resource_access": { "epistola-suite": { "roles": ["tenant-manager"] } } }
     * ```
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractPlatformRoles(oauth2User: OAuth2User): Set<PlatformRole> {
        val resourceAccess = oauth2User.getAttribute<Map<String, Any>>("resource_access") ?: return emptySet()
        val clientAccess = resourceAccess[CLIENT_ID] as? Map<String, Any> ?: return emptySet()
        val roles = clientAccess["roles"] as? List<*> ?: return emptySet()

        return roles.mapNotNull { roleName ->
            when (roleName?.toString()) {
                "tenant-manager" -> PlatformRole.TENANT_MANAGER
                else -> {
                    logger.warn("Unknown platform role in OIDC claim: {}", roleName)
                    null
                }
            }
        }.toSet()
    }

    companion object {
        /** The Keycloak client ID used to scope platform roles in `resource_access`. */
        private const val CLIENT_ID = "epistola-suite"

        /**
         * Derives the [AuthProvider] from the OAuth2 registration ID.
         */
        fun deriveAuthProvider(registrationId: String): AuthProvider = when (registrationId.lowercase()) {
            "keycloak" -> AuthProvider.KEYCLOAK
            else -> AuthProvider.GENERIC_OIDC
        }
    }
}

/**
 * Extension to convert User to EpistolaPrincipal.
 */
private fun User.toEpistolaPrincipal(
    memberships: Map<TenantKey, Set<TenantRole>> = tenantMemberships,
    platformRoles: Set<PlatformRole> = emptySet(),
) = EpistolaPrincipal(
    userId = id,
    externalId = externalId,
    email = email,
    displayName = displayName,
    tenantMemberships = memberships,
    platformRoles = platformRoles,
    currentTenantId = null, // Will be set by tenant selector later
)

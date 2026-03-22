package app.epistola.suite.security

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.users.AuthProvider
import app.epistola.suite.users.User
import app.epistola.suite.users.commands.CreateUser
import app.epistola.suite.users.commands.SyncTenantMemberships
import app.epistola.suite.users.commands.UpdateLastLogin
import app.epistola.suite.users.queries.GetUserByExternalId
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.oidc.user.OidcUser
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
 * 5. Parses group memberships from `groups` attribute via [parseGroupMemberships]
 * 6. Converts to EpistolaPrincipal for use by business logic
 *
 * The [AuthProvider] is derived from the OAuth2 registration ID:
 * - `"keycloak"` → [AuthProvider.KEYCLOAK]
 * - anything else → [AuthProvider.GENERIC_OIDC]
 *
 * Only loaded when the Keycloak OAuth2 client registration is configured.
 */
@Component
@ConditionalOnProperty(prefix = "spring.security.oauth2.client.registration.keycloak", name = ["client-id"])
class OAuth2UserProvisioningService(
    private val mediator: Mediator,
    private val authProperties: AuthProperties,
    private val membershipResolver: LoginMembershipResolver? = null,
) : OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private val delegate = DefaultOAuth2UserService()
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun loadUser(request: OAuth2UserRequest): OAuth2User {
        val oauth2User = delegate.loadUser(request)
        val principal = provision(oauth2User, request.clientRegistration.registrationId)
        return OAuth2UserWrapper(oauth2User, principal)
    }

    private fun getOrCreateUser(
        externalId: String,
        email: String,
        displayName: String,
        provider: AuthProvider,
    ): User {
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

        if (!authProperties.autoProvision) {
            throw OAuth2AuthenticationException(
                OAuth2Error("auto_provision_disabled"),
                "User not found and auto-provisioning is disabled",
            )
        }

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
     * Extracts the groups list from the OAuth2 user's attributes.
     * The `groups` attribute may be a List<String> from the OIDC userinfo endpoint.
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractGroupsList(oauth2User: OAuth2User): List<String> {
        val groups = oauth2User.getAttribute<Any>("groups") ?: return emptyList()
        return when (groups) {
            is List<*> -> groups.filterIsInstance<String>()
            else -> {
                logger.warn("Unexpected groups attribute type: {}", groups::class.simpleName)
                emptyList()
            }
        }
    }

    /**
     * Provisions the user and returns an EpistolaPrincipal.
     * Shared between OAuth2 and OIDC flows.
     */
    fun provision(oauth2User: OAuth2User, registrationId: String): EpistolaPrincipal {
        val provider = deriveAuthProvider(registrationId)

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

        val user = try {
            getOrCreateUser(externalId, email, displayName, provider)
        } catch (e: Exception) {
            logger.error("Failed to provision user: $email", e)
            throw OAuth2AuthenticationException(
                OAuth2Error("user_provisioning_failed"),
                "Failed to provision user",
                e,
            )
        }

        updateLastLogin(user.id)

        val groups = extractGroupsList(oauth2User)
        val parsed = parseGroupMemberships(groups)

        val tokenMemberships = parsed.tenantRoles
        val mergedMemberships = if (tokenMemberships.isNotEmpty() || parsed.globalRoles.isNotEmpty()) {
            tokenMemberships
        } else if (membershipResolver != null) {
            val resolved = membershipResolver.resolve(email, user)
            if (resolved != null) {
                return user.toEpistolaPrincipal(
                    memberships = resolved.tenantMemberships,
                    globalRoles = resolved.globalRoles,
                    platformRoles = resolved.platformRoles,
                )
            }
            user.tenantMemberships
        } else {
            user.tenantMemberships
        }

        if (tokenMemberships.isNotEmpty()) {
            try {
                mediator.send(SyncTenantMemberships(user.id, tokenMemberships))
            } catch (e: Exception) {
                logger.warn("Failed to sync tenant memberships for user {}: {}", user.email, e.message)
            }
        }

        return user.toEpistolaPrincipal(
            memberships = mergedMemberships,
            globalRoles = parsed.globalRoles,
            platformRoles = parsed.platformRoles,
        )
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
            private const val serialVersionUID: Long = 2L
        }
    }

    /**
     * Creates an OIDC-specific user service that delegates provisioning to this service.
     * Spring Security uses a separate service type for OpenID Connect providers.
     */
    fun asOidcUserService(): OAuth2UserService<OidcUserRequest, OidcUser> {
        val oidcDelegate = OidcUserService()
        return OAuth2UserService<OidcUserRequest, OidcUser> { request ->
            val oidcUser = oidcDelegate.loadUser(request)
            val principal = provision(oidcUser, request.clientRegistration.registrationId)
            logger.info("OIDC login: provisioned user {} with {} memberships", principal.email, principal.tenantMemberships.size)
            OidcUserWrapper(oidcUser, principal)
        }
    }

    companion object {
        /**
         * Derives the [AuthProvider] from the OAuth2 registration ID.
         */
        fun deriveAuthProvider(registrationId: String): AuthProvider = when (registrationId.lowercase()) {
            "keycloak" -> AuthProvider.KEYCLOAK
            else -> AuthProvider.GENERIC_OIDC
        }
    }

    private class OidcUserWrapper(
        private val delegate: OidcUser,
        override val epistolaPrincipal: EpistolaPrincipal,
    ) : OidcUser by delegate,
        EpistolaPrincipalHolder,
        java.io.Serializable {

        companion object {
            private const val serialVersionUID: Long = 3L
        }
    }
}

/**
 * Extension to convert User to EpistolaPrincipal.
 */
private fun User.toEpistolaPrincipal(
    memberships: Map<TenantKey, Set<TenantRole>> = tenantMemberships,
    globalRoles: Set<TenantRole> = emptySet(),
    platformRoles: Set<PlatformRole> = emptySet(),
) = EpistolaPrincipal(
    userId = id,
    externalId = externalId,
    email = email,
    displayName = displayName,
    tenantMemberships = memberships,
    globalRoles = globalRoles,
    platformRoles = platformRoles,
    currentTenantId = null,
)

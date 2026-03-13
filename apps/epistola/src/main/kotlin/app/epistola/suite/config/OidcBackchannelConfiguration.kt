package app.epistola.suite.config

import app.epistola.suite.security.AuthProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder

/**
 * Overrides Spring Boot's auto-configured OAuth2 beans when a backchannel base URL is set.
 *
 * In Docker/Kubernetes environments, the identity provider (e.g. Keycloak) is reachable
 * by different URLs from the browser vs. from backend containers:
 * - Browser: http://localhost:8081 (external)
 * - Container: http://keycloak:8080 (internal)
 *
 * This configuration builds ClientRegistrations directly from properties (no OIDC discovery)
 * and rewrites server-to-server endpoints (token, JWK, userinfo) to use the internal URL,
 * while keeping browser-facing endpoints (authorization, issuer) external.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("epistola.auth.oidc.backchannel-base-url")
@EnableConfigurationProperties(OAuth2ClientProperties::class)
class OidcBackchannelConfiguration(
    private val authProperties: AuthProperties,
    private val oauth2ClientProperties: OAuth2ClientProperties,
) {
    private val log = LoggerFactory.getLogger(OidcBackchannelConfiguration::class.java)

    @Bean
    fun clientRegistrationRepository(): ClientRegistrationRepository {
        val backchannelBaseUrl = authProperties.oidc.backchannelBaseUrl
            ?: error("backchannel-base-url must be set")

        log.info("Configuring OIDC backchannel: server-to-server calls will use {}", backchannelBaseUrl)

        val registrations = oauth2ClientProperties.registration.map { (registrationId, reg) ->
            val providerName = reg.provider ?: registrationId
            val provider = oauth2ClientProperties.provider[providerName]
                ?: error("No provider '$providerName' configured for registration '$registrationId'")

            val issuerUri = provider.issuerUri
                ?: error("issuer-uri is required for provider '$providerName'")

            val issuerPath = extractPath(issuerUri)
            val oidcBase = "$issuerUri/protocol/openid-connect"
            val backchannelOidcBase = "$backchannelBaseUrl$issuerPath/protocol/openid-connect"

            ClientRegistration.withRegistrationId(registrationId)
                .clientId(reg.clientId)
                .clientSecret(reg.clientSecret)
                .scope(reg.scope)
                .redirectUri(reg.redirectUri ?: "{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationGrantType(
                    AuthorizationGrantType(reg.authorizationGrantType ?: "authorization_code"),
                )
                .clientName(reg.clientName ?: registrationId)
                // Browser-facing: keep external issuer URL
                .authorizationUri("$oidcBase/auth")
                .issuerUri(issuerUri)
                // Server-to-server: use backchannel URL
                .tokenUri("$backchannelOidcBase/token")
                .userInfoUri("$backchannelOidcBase/userinfo")
                .jwkSetUri("$backchannelOidcBase/certs")
                .userNameAttributeName(provider.userNameAttribute ?: "sub")
                .build()
        }

        return InMemoryClientRegistrationRepository(registrations)
    }

    @Bean
    fun jwtDecoder(): JwtDecoder {
        val backchannelBaseUrl = authProperties.oidc.backchannelBaseUrl
            ?: error("backchannel-base-url must be set")

        val firstProvider = oauth2ClientProperties.provider.values.first()
        val issuerUri = firstProvider.issuerUri
            ?: error("issuer-uri is required for JWT decoder")

        val issuerPath = extractPath(issuerUri)
        val backchannelJwkUri = "$backchannelBaseUrl$issuerPath/protocol/openid-connect/certs"

        log.info("JWT decoder: fetching JWK from {}, validating issuer against {}", backchannelJwkUri, issuerUri)

        return NimbusJwtDecoder.withJwkSetUri(backchannelJwkUri).build().apply {
            setJwtValidator(JwtValidators.createDefaultWithIssuer(issuerUri))
        }
    }

    companion object {
        /**
         * Extracts the path component from a URL.
         * e.g. "http://localhost:8081/realms/valtimo" -> "/realms/valtimo"
         */
        fun extractPath(url: String): String {
            val withoutScheme = url.substringAfter("://")
            val pathStart = withoutScheme.indexOf('/')
            return if (pathStart >= 0) withoutScheme.substring(pathStart) else ""
        }

        /**
         * Rewrites a URL to use the backchannel base URL, keeping the original path.
         */
        fun rewriteUrl(url: String?, backchannelBaseUrl: String): String? {
            if (url == null) return null
            val path = extractPath(url)
            return "$backchannelBaseUrl$path"
        }
    }
}

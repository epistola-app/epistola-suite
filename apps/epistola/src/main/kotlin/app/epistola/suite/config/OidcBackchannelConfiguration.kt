package app.epistola.suite.config

import app.epistola.suite.security.AuthProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientPropertiesMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
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
 * This configuration rewrites server-to-server endpoints (token, JWK, userinfo) to use
 * the internal URL, while keeping browser-facing endpoints (authorization, issuer) external.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("epistola.auth.oidc.backchannel-base-url")
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

        // Let Spring map all the properties (including issuer discovery) into ClientRegistrations
        val mapper = OAuth2ClientPropertiesMapper(oauth2ClientProperties)
        val original = mapper.asClientRegistrations()

        val registrations = original.map { (registrationId, reg) ->
            rewriteBackchannelEndpoints(registrationId, reg, backchannelBaseUrl)
        }

        return InMemoryClientRegistrationRepository(registrations)
    }

    @Bean
    fun jwtDecoder(): JwtDecoder {
        val backchannelBaseUrl = authProperties.oidc.backchannelBaseUrl
            ?: error("backchannel-base-url must be set")

        // Find the first provider with an issuer-uri to determine the external issuer
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

    private fun rewriteBackchannelEndpoints(
        registrationId: String,
        reg: ClientRegistration,
        backchannelBaseUrl: String,
    ): ClientRegistration {
        val provider = reg.providerDetails
        val tokenUri = provider.tokenUri
        val userInfoUri = provider.userInfoEndpoint.uri
        val jwkSetUri = provider.jwkSetUri

        return ClientRegistration.withClientRegistration(reg)
            .tokenUri(rewriteUrl(tokenUri, backchannelBaseUrl))
            .userInfoUri(rewriteUrl(userInfoUri, backchannelBaseUrl))
            .jwkSetUri(rewriteUrl(jwkSetUri, backchannelBaseUrl))
            .build()
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
         * e.g. rewriteUrl("http://localhost:8081/realms/valtimo/protocol/openid-connect/token",
         *                  "http://keycloak:8080")
         *      -> "http://keycloak:8080/realms/valtimo/protocol/openid-connect/token"
         */
        fun rewriteUrl(url: String?, backchannelBaseUrl: String): String? {
            if (url == null) return null
            val path = extractPath(url)
            return "$backchannelBaseUrl$path"
        }
    }
}

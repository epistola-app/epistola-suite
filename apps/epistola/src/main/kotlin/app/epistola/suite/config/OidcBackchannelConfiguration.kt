package app.epistola.suite.config

import app.epistola.suite.security.AuthProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * Overrides Spring Boot's auto-configured OAuth2 beans when a backchannel base URL is set.
 *
 * In Docker/Kubernetes environments, the identity provider (Keycloak, authentik, …) is reachable
 * by different URLs from the browser vs. from backend containers:
 * - Browser: https://sso.example.com (external)
 * - Container: http://sso:9000 (internal)
 *
 * This configuration reads the provider's real endpoint **paths** from its **OIDC discovery
 * document** (`/.well-known/openid-configuration`) rather than assuming a provider-specific URL
 * layout, so it works for any compliant provider. It fetches discovery over the **internal**
 * (container-reachable) URL, then recombines each endpoint's path with the host we control: the
 * browser-facing authorization endpoint (and the issuer claim) use the **external** issuer host,
 * while the server-to-server endpoints (token, userinfo, JWK) use the **internal** backchannel host.
 * Only the discovered *paths* are trusted — never the host the document advertises — so it is robust
 * to providers that reflect the request host in their discovery document.
 *
 * Requirement: the IdP must validate/issue tokens under the **external** `issuer-uri` (e.g. Keycloak
 * `KC_HOSTNAME`, authentik's configured base URL). The token `iss` is validated against that external
 * issuer, so an IdP that stamps its internal host into `iss` will be rejected — configure a stable
 * external hostname on the provider.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("epistola.auth.oidc.backchannel-base-url")
@EnableConfigurationProperties(OAuth2ClientProperties::class)
class OidcBackchannelConfiguration(
    private val authProperties: AuthProperties,
    private val oauth2ClientProperties: OAuth2ClientProperties,
) {
    private val log = LoggerFactory.getLogger(OidcBackchannelConfiguration::class.java)

    // Discovery runs at startup; bound the wait so a slow/unreachable IdP fails fast instead of
    // hanging context refresh (and the pod's readiness) indefinitely.
    private val restClient = RestClient.builder()
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(5))
                setReadTimeout(Duration.ofSeconds(5))
            },
        )
        .build()

    // Memoized per external issuer so the two beans below don't each fetch the same document.
    private val metadataCache = mutableMapOf<String, OidcMetadata>()

    @Bean
    fun clientRegistrationRepository(): ClientRegistrationRepository {
        val backchannelBaseUrl = requireBackchannelBaseUrl()

        log.info("Configuring OIDC backchannel: server-to-server calls will use {}", backchannelBaseUrl)

        val registrations = oauth2ClientProperties.registration.map { (registrationId, reg) ->
            val providerName = reg.provider ?: registrationId
            val provider = oauth2ClientProperties.provider[providerName]
                ?: error("No provider '$providerName' configured for registration '$registrationId'")

            val issuerUri = provider.issuerUri
                ?: error("issuer-uri is required for provider '$providerName'")

            val metadata = fetchOidcMetadata(issuerUri, backchannelBaseUrl)
            val externalBaseUrl = originOf(issuerUri)

            ClientRegistration.withRegistrationId(registrationId)
                .clientId(reg.clientId ?: error("client-id is required for registration '$registrationId'"))
                .clientSecret(reg.clientSecret)
                .scope(reg.scope)
                .redirectUri(reg.redirectUri ?: "{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationGrantType(
                    AuthorizationGrantType(reg.authorizationGrantType ?: "authorization_code"),
                )
                .clientName(reg.clientName ?: registrationId)
                // Browser-facing: recombine the discovered auth path with the EXTERNAL issuer host
                // (never the host the discovery doc advertised — providers may reflect the internal one)
                .authorizationUri(rewriteUrl(metadata.authorizationEndpoint, externalBaseUrl)!!)
                .issuerUri(issuerUri)
                // Server-to-server: recombine the discovered path with the internal backchannel host
                .tokenUri(rewriteUrl(metadata.tokenEndpoint, backchannelBaseUrl)!!)
                .userInfoUri(rewriteUrl(metadata.userinfoEndpoint, backchannelBaseUrl))
                .jwkSetUri(rewriteUrl(metadata.jwksUri, backchannelBaseUrl)!!)
                .userNameAttributeName(provider.userNameAttribute ?: "sub")
                .build()
        }

        return InMemoryClientRegistrationRepository(registrations)
    }

    @Bean
    fun jwtDecoder(): JwtDecoder {
        val backchannelBaseUrl = requireBackchannelBaseUrl()

        val providers = oauth2ClientProperties.provider.values
        require(providers.size == 1) {
            "OIDC backchannel supports a single provider per deployment, but ${providers.size} are configured"
        }
        val issuerUri = providers.first().issuerUri
            ?: error("issuer-uri is required for JWT decoder")

        val metadata = fetchOidcMetadata(issuerUri, backchannelBaseUrl)
        val backchannelJwkUri = rewriteUrl(metadata.jwksUri, backchannelBaseUrl)!!

        log.info("JWT decoder: fetching JWK from {}, validating issuer against {}", backchannelJwkUri, issuerUri)

        return NimbusJwtDecoder.withJwkSetUri(backchannelJwkUri).build().apply {
            setJwtValidator(JwtValidators.createDefaultWithIssuer(issuerUri))
        }
    }

    private fun requireBackchannelBaseUrl(): String = authProperties.oidc.backchannelBaseUrl ?: error("backchannel-base-url must be set")

    /**
     * Fetches the provider's OIDC discovery document.
     *
     * The document is retrieved over the **internal** (backchannel) well-known URL so it is reachable
     * from inside the container even when the external issuer host is not. The endpoint URLs *inside*
     * the document are whatever the provider advertises (typically its external host) — callers then
     * host-rewrite the back-channel endpoints as needed.
     */
    private fun fetchOidcMetadata(externalIssuer: String, backchannelBaseUrl: String): OidcMetadata = metadataCache.getOrPut(externalIssuer) { discoverOidcMetadata(externalIssuer, backchannelBaseUrl) }

    private fun discoverOidcMetadata(externalIssuer: String, backchannelBaseUrl: String): OidcMetadata {
        val internalWellKnown = rewriteUrl(wellKnownUrl(externalIssuer), backchannelBaseUrl)!!
        log.info("Discovering OIDC metadata from {}", internalWellKnown)

        val doc = try {
            restClient.get().uri(internalWellKnown).retrieve().body(Map::class.java)
        } catch (e: Exception) {
            error("Failed to fetch OIDC discovery document from $internalWellKnown: ${e.message}")
        } ?: error("Empty OIDC discovery document from $internalWellKnown")

        fun required(key: String): String = (doc[key] as? String) ?: error("OIDC discovery document from $internalWellKnown is missing '$key'")

        return OidcMetadata(
            authorizationEndpoint = required("authorization_endpoint"),
            tokenEndpoint = required("token_endpoint"),
            userinfoEndpoint = doc["userinfo_endpoint"] as? String,
            jwksUri = required("jwks_uri"),
        )
    }

    private data class OidcMetadata(
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val userinfoEndpoint: String?,
        val jwksUri: String,
    )

    companion object {
        /**
         * Builds the OIDC discovery URL for an issuer, tolerating a trailing slash
         * (Keycloak issuers have none; authentik issuers end with `/`).
         */
        fun wellKnownUrl(issuerUri: String): String = "${issuerUri.trimEnd('/')}/.well-known/openid-configuration"

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
         * Returns the scheme+host+port origin of a URL (everything before the path).
         * e.g. "https://sso.example.com/application/o/app/" -> "https://sso.example.com"
         */
        fun originOf(url: String): String = url.removeSuffix(extractPath(url))

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

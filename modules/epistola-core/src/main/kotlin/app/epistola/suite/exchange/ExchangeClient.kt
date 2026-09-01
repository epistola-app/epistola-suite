// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.util.UUID

/**
 * Exchange was reachable, but its answer cannot be used: a document version Suite does not
 * understand, an issuer that does not match, a missing field, an endpoint that is not HTTPS.
 *
 * Distinct from a transport failure (`RestClientException`) because the remedy is different — this
 * is a configuration or contract problem an operator has to look at — and distinct from a bare
 * `IllegalArgumentException` so callers can surface it as an explanation rather than a stack trace.
 * The messages are safe to show: they name URLs and protocol fields, never credentials.
 */
class ExchangeProtocolException(message: String) : RuntimeException(message)

data class ExchangeEndpoints(
    val issuer: String,
    val baseUrl: String,
    val authorizationRequestEndpoint: String,
    val tokenEndpoint: String,
)

data class ExchangeAuthorizationResponse(
    val authorizationUri: String,
    val expiresIn: Duration,
)

data class ExchangeTokenResponse(
    val oauthApplicationId: UUID,
    val clientSecret: String?,
    val tenantConnectionId: UUID,
    val accessToken: String,
    val accessTokenExpiresIn: Duration,
    val refreshToken: String,
    val refreshTokenExpiresIn: Duration,
    val scopes: Set<String>,
)

data class ExchangeConnectionContext(
    val tenantConnectionId: UUID,
    val tenantConnectionReference: String,
    val organizationSlug: String,
    val organizationName: String,
    val scopes: Set<String>,
    val namespaces: Set<String>,
)

data class ExchangePublicationResponse(
    val id: UUID,
    val state: String,
    val errorCode: String?,
    val errorDetail: String?,
)

@Component
class ExchangeClient(
    private val properties: ExchangeProperties,
    @Qualifier("exchangeRestClient") private val http: RestClient,
) {

    fun endpoints(): ExchangeEndpoints {
        val configuredBase = properties.configuredBaseUrl?.trimEnd('/')
        val discovery = configuredBase?.let { DiscoveryDocument(1, it, it) } ?: run {
            val node = http.get().uri(properties.discoveryUrl).retrieve().body(JsonNode::class.java)
                ?: throw ExchangeProtocolException("Exchange discovery returned an empty response")
            val version = node.path("version").asInt()
            if (version != 1) throw ExchangeProtocolException("Unsupported Exchange discovery version: $version")
            DiscoveryDocument(1, node.requiredText("issuer"), node.requiredText("baseUrl"))
        }
        val issuer = requireSecure(discovery.issuer.trimEnd('/'), "issuer")
        val baseUrl = requireSecure(discovery.baseUrl.trimEnd('/'), "base URL")
        val metadata = http.get().uri("$issuer/.well-known/oauth-authorization-server").retrieve().body(JsonNode::class.java)
            ?: throw ExchangeProtocolException("Exchange OAuth discovery returned an empty response")
        val advertised = metadata.requiredText("issuer").trimEnd('/')
        if (advertised != issuer) {
            throw ExchangeProtocolException(
                "Exchange OAuth issuer mismatch: discovery says '$issuer' but its OAuth metadata advertises '$advertised'",
            )
        }
        return ExchangeEndpoints(
            issuer = issuer,
            baseUrl = baseUrl,
            authorizationRequestEndpoint = requireSecure(metadata.requiredText("authorization_request_endpoint"), "authorization endpoint"),
            tokenEndpoint = requireSecure(metadata.requiredText("token_endpoint"), "token endpoint"),
        )
    }

    /**
     * The client secret, refresh token and whole catalog archive cross these URLs, so plaintext is
     * refused unless a local checkout has explicitly opted in.
     */
    private fun requireSecure(url: String, what: String): String {
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw ExchangeProtocolException("Exchange $what is not an absolute URL: '$url'")
        }
        if (!properties.allowHttp && url.startsWith("http://")) {
            throw ExchangeProtocolException(
                "Exchange $what must use HTTPS ('$url'); set epistola.exchange.allow-http only for a local Exchange",
            )
        }
        return url
    }

    fun startAuthorization(
        endpoints: ExchangeEndpoints,
        tenantName: String,
        deploymentId: String,
        redirectUri: String,
        state: String,
        codeChallenge: String,
        existingApplicationId: UUID?,
        existingConnectionId: UUID?,
        existingOrganizationSlug: String?,
    ): ExchangeAuthorizationResponse {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("application_name", "Epistola Suite")
            add("tenant_name", tenantName)
            add("installation_id", deploymentId)
            add("redirect_uri", redirectUri)
            add("state", state)
            add("code_challenge", codeChallenge)
            add("code_challenge_method", "S256")
            add("scope", "read publish")
            existingApplicationId?.let { add("application_id", it.toString()) }
            existingConnectionId?.let { add("tenant_connection_id", it.toString()) }
            // A reauthorization renews an identity and may not replace one, so the organization is
            // already decided. Sending it means Exchange states it instead of offering a choice
            // where only one answer is valid - and where a wrong one is refused after approving,
            // not before.
            existingOrganizationSlug?.let { add("organization", it) }
        }
        val node = http.post().uri(endpoints.authorizationRequestEndpoint)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode::class.java)
            ?: throw ExchangeProtocolException("Exchange authorization request returned an empty response")
        return ExchangeAuthorizationResponse(
            node.requiredText("authorization_uri"),
            Duration.ofSeconds(node.path("expires_in").asLong()),
        )
    }

    fun exchangeAuthorizationCode(
        endpoints: ExchangeEndpoints,
        code: String,
        applicationId: UUID,
        redirectUri: String,
        codeVerifier: String,
        clientSecret: String?,
    ): ExchangeTokenResponse = token(
        endpoints,
        linkedMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "client_id" to applicationId.toString(),
            "redirect_uri" to redirectUri,
            "code_verifier" to codeVerifier,
        ).also { values -> clientSecret?.let { values["client_secret"] = it } },
    )

    fun refresh(
        endpoints: ExchangeEndpoints,
        refreshToken: String,
        applicationId: UUID,
        clientSecret: String,
    ): ExchangeTokenResponse = token(
        endpoints,
        linkedMapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to applicationId.toString(),
            "client_secret" to clientSecret,
        ),
    )

    fun context(endpoints: ExchangeEndpoints, accessToken: String): ExchangeConnectionContext {
        val node = http.get().uri("${endpoints.baseUrl}/api/v1/tenant-connection")
            .headers { it.setBearerAuth(accessToken) }.retrieve().body(JsonNode::class.java)
            ?: throw ExchangeProtocolException("Exchange tenant context returned an empty response")
        return ExchangeConnectionContext(
            UUID.fromString(node.requiredText("tenantConnectionId")),
            node.requiredText("tenantConnectionReference"),
            node.path("organization").requiredText("slug"),
            node.path("organization").requiredText("name"),
            node.path("scopes").mapTo(mutableSetOf()) { it.stringValue() },
            node.path("namespaces").mapTo(mutableSetOf()) { it.requiredText("slug") },
        )
    }

    fun disconnect(baseUrl: String, accessToken: String) {
        http.delete().uri("${baseUrl.trimEnd('/')}/api/v1/tenant-connection")
            .headers { it.setBearerAuth(accessToken) }
            .retrieve()
            .toBodilessEntity()
    }

    fun submit(
        baseUrl: String,
        accessToken: String,
        namespace: String,
        archive: ByteArray,
        idempotencyKey: UUID,
    ): ExchangePublicationResponse {
        val multipart = MultipartBodyBuilder().apply {
            part("namespace", namespace)
            part(
                "catalog",
                object : ByteArrayResource(archive) {
                    override fun getFilename(): String = "catalog.zip"
                },
            ).contentType(MediaType.parseMediaType("application/zip"))
        }.build()
        val node = http.post().uri("${baseUrl.trimEnd('/')}/api/v1/publication-submissions")
            .headers {
                it.setBearerAuth(accessToken)
                it.set("Idempotency-Key", idempotencyKey.toString())
            }.contentType(MediaType.MULTIPART_FORM_DATA).body(multipart)
            .retrieve().body(JsonNode::class.java) ?: throw ExchangeProtocolException("Exchange publication returned an empty response")
        return publication(node)
    }

    fun publication(baseUrl: String, accessToken: String, id: UUID): ExchangePublicationResponse {
        val node = http.get().uri("${baseUrl.trimEnd('/')}/api/v1/publication-submissions/$id")
            .headers { it.setBearerAuth(accessToken) }.retrieve().body(JsonNode::class.java)
            ?: throw ExchangeProtocolException("Exchange publication status returned an empty response")
        return publication(node)
    }

    private fun token(endpoints: ExchangeEndpoints, values: Map<String, String>): ExchangeTokenResponse {
        val form = LinkedMultiValueMap<String, String>().apply { values.forEach(::add) }
        val node = http.post().uri(endpoints.tokenEndpoint).contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form).retrieve().body(JsonNode::class.java)
            ?: throw ExchangeProtocolException("Exchange token endpoint returned an empty response")
        return ExchangeTokenResponse(
            UUID.fromString(node.requiredText("client_id")),
            node.path("client_secret").takeUnless { it.isMissingNode || it.isNull }?.stringValue(),
            UUID.fromString(node.requiredText("tenant_connection_id")),
            node.requiredText("access_token"),
            Duration.ofSeconds(node.path("expires_in").asLong()),
            node.requiredText("refresh_token"),
            Duration.ofSeconds(node.path("refresh_token_expires_in").asLong()),
            node.requiredText("scope").split(' ').filter(String::isNotBlank).toSet(),
        )
    }

    private data class DiscoveryDocument(val version: Int, val issuer: String, val baseUrl: String)

    private fun publication(node: JsonNode) = ExchangePublicationResponse(
        UUID.fromString(node.requiredText("id")),
        node.requiredText("state"),
        node.path("errorCode").takeUnless { it.isMissingNode || it.isNull }?.stringValue(),
        node.path("errorDetail").takeUnless { it.isMissingNode || it.isNull }?.stringValue(),
    )

    private fun JsonNode.requiredText(name: String): String = path(name).stringValue()?.takeIf(String::isNotBlank)
        ?: throw ExchangeProtocolException("Exchange response is missing '$name'")
}

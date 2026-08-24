// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID

data class ExchangeEndpoints(
    val issuer: String,
    val baseUrl: String,
    val deviceAuthorizationEndpoint: String,
    val tokenEndpoint: String,
)

data class ExchangeDeviceCodeResponse(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresIn: Duration,
    val interval: Duration,
)

data class ExchangeTokenResponse(
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

class ExchangeAuthorizationPendingException : RuntimeException()

@Component
class ExchangeClient(
    private val properties: ExchangeProperties,
    private val objectMapper: ObjectMapper,
) {
    private val http = RestClient.create()

    fun endpoints(): ExchangeEndpoints {
        val configuredBase = properties.baseUrl?.trimEnd('/')
        val discovery = configuredBase?.let { DiscoveryDocument(1, it, it) } ?: run {
            val node = http.get().uri(properties.discoveryUrl).retrieve().body(JsonNode::class.java)
                ?: error("Exchange discovery returned an empty response")
            require(node.path("version").asInt() == 1) { "Unsupported Exchange discovery version" }
            DiscoveryDocument(1, node.requiredText("issuer"), node.requiredText("baseUrl"))
        }
        val issuer = discovery.issuer.trimEnd('/')
        val metadata = http.get().uri("$issuer/.well-known/oauth-authorization-server").retrieve().body(JsonNode::class.java)
            ?: error("Exchange OAuth discovery returned an empty response")
        require(metadata.requiredText("issuer").trimEnd('/') == issuer) { "Exchange OAuth issuer mismatch" }
        return ExchangeEndpoints(
            issuer = issuer,
            baseUrl = discovery.baseUrl.trimEnd('/'),
            deviceAuthorizationEndpoint = metadata.requiredText("device_authorization_endpoint"),
            tokenEndpoint = metadata.requiredText("token_endpoint"),
        )
    }

    fun startDeviceAuthorization(
        endpoints: ExchangeEndpoints,
        tenantName: String,
        deploymentId: String,
        existingConnectionId: UUID?,
    ): ExchangeDeviceCodeResponse {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("tenant_name", tenantName)
            add("deployment_id", deploymentId)
            add("scope", "read publish")
            existingConnectionId?.let { add("tenant_connection_id", it.toString()) }
        }
        val node = http.post().uri(endpoints.deviceAuthorizationEndpoint)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode::class.java)
            ?: error("Exchange device authorization returned an empty response")
        return ExchangeDeviceCodeResponse(
            node.requiredText("device_code"),
            node.requiredText("user_code"),
            node.requiredText("verification_uri"),
            node.requiredText("verification_uri_complete"),
            Duration.ofSeconds(node.path("expires_in").asLong()),
            Duration.ofSeconds(node.path("interval").asLong()),
        )
    }

    fun pollDeviceToken(endpoints: ExchangeEndpoints, deviceCode: String): ExchangeTokenResponse = token(
        endpoints,
        linkedMapOf(
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            "device_code" to deviceCode,
        ),
    )

    fun refresh(endpoints: ExchangeEndpoints, refreshToken: String): ExchangeTokenResponse = token(
        endpoints,
        linkedMapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken),
    )

    fun context(endpoints: ExchangeEndpoints, accessToken: String): ExchangeConnectionContext {
        val node = http.get().uri("${endpoints.baseUrl}/api/v1/tenant-connection")
            .headers { it.setBearerAuth(accessToken) }.retrieve().body(JsonNode::class.java)
            ?: error("Exchange tenant context returned an empty response")
        return ExchangeConnectionContext(
            UUID.fromString(node.requiredText("tenantConnectionId")),
            node.requiredText("tenantConnectionReference"),
            node.path("organization").requiredText("slug"),
            node.path("organization").requiredText("name"),
            node.path("scopes").mapTo(mutableSetOf()) { it.stringValue() },
            node.path("namespaces").mapTo(mutableSetOf()) { it.requiredText("slug") },
        )
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
            .retrieve().body(JsonNode::class.java) ?: error("Exchange publication returned an empty response")
        return publication(node)
    }

    fun publication(baseUrl: String, accessToken: String, id: UUID): ExchangePublicationResponse {
        val node = http.get().uri("${baseUrl.trimEnd('/')}/api/v1/publication-submissions/$id")
            .headers { it.setBearerAuth(accessToken) }.retrieve().body(JsonNode::class.java)
            ?: error("Exchange publication status returned an empty response")
        return publication(node)
    }

    private fun token(endpoints: ExchangeEndpoints, values: Map<String, String>): ExchangeTokenResponse {
        val form = LinkedMultiValueMap<String, String>().apply { values.forEach(::add) }
        val node = try {
            http.post().uri(endpoints.tokenEndpoint).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(JsonNode::class.java)
        } catch (failure: HttpClientErrorException.BadRequest) {
            val error = runCatching { objectMapper.readTree(failure.responseBodyAsByteArray).path("error").stringValue() }.getOrNull()
            if (error == "authorization_pending") throw ExchangeAuthorizationPendingException()
            throw failure
        } ?: error("Exchange token endpoint returned an empty response")
        return ExchangeTokenResponse(
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
        ?: error("Exchange response is missing '$name'")
}

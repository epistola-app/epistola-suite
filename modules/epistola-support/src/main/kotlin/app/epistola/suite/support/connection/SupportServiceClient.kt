// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import java.nio.channels.UnresolvedAddressException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.CancellationException

/**
 * What a registration answered: the client id, the current claim code and the registration's status.
 *
 * @property claimCodeExpiresAt `epistola_claim_code_expires_at`: when the service deletes the
 *   registration if nobody activates it; null when it did not say.
 */
data class SupportRegistration(val clientId: String, val claimCode: String, val status: String, val claimCodeExpiresAt: Instant? = null)

/** A token as the token endpoint issued it. */
data class SupportToken(val accessToken: String, val expiresInSeconds: Long, val scopes: Set<SupportScope>)

/** `GET {api}/connection`: the registration as the support service sees it. */
data class SupportConnectionInfo(
    val clientId: String,
    val status: String,
    val scopes: Set<SupportScope>,
    /** While PENDING: when the service deletes the registration if nobody activates it. */
    val claimCodeExpiresAt: Instant? = null,
    /** While DECLINED: when Epistola declined it. */
    val declinedAt: Instant? = null,
    /** Services Epistola paused now; null when none. */
    val pause: SupportPause? = null,
)

/**
 * The unauthenticated calls of the support protocol (`docs/support-protocol.md`): metadata,
 * registration, the token endpoint, and the connection status. Every failure is a
 * [SupportUnavailableException] except the service's JSON `invalid_client` at the token endpoint,
 * which is a [SupportRefusedException]. Calls go through [SupportHttp]: a deadline for the whole
 * call and a cap on the answer.
 */
@Component
class SupportServiceClient(
    supportHttp: SupportHttp,
    private val objectMapper: ObjectMapper,
) {
    private val http: RestClient = RestClient.builder().requestFactory(supportHttp.requestFactory()).build()

    fun metadata(issuer: URI, allowHttp: Boolean): SupportServiceMetadata {
        val document = call("reading the support service metadata") {
            http.get().uri(SupportServiceMetadata.location(issuer)).accept(MediaType.APPLICATION_JSON).retrieve().body(JsonNode::class.java)
        } ?: throw SupportUnavailableException("The support service metadata was empty")
        return SupportServiceMetadata.verify(issuer, document, allowHttp)
    }

    fun register(metadata: SupportServiceMetadata, request: Map<String, Any?>): SupportRegistration {
        val node = call("registering with the support service") {
            http.post().uri(metadata.registrationEndpoint).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(request.filterValues { it != null })).retrieve().body(JsonNode::class.java)
        } ?: throw SupportUnavailableException("The support service answered the registration with nothing")
        return SupportRegistration(
            clientId = node.requiredText("client_id"),
            claimCode = node.requiredText("epistola_claim_code"),
            status = node.str("epistola_status") ?: "PENDING",
            claimCodeExpiresAt = node.instant("epistola_claim_code_expires_at"),
        )
    }

    /**
     * Requests a token for [scopes], sent as `scope` with the names space-separated: empty asks for
     * no scope (`scope=`), which the support service answers with a token that carries none.
     */
    fun token(metadata: SupportServiceMetadata, clientId: String, assertion: String, scopes: Set<String>): SupportToken {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "client_credentials")
            add("client_id", clientId)
            add("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
            add("client_assertion", assertion)
            add("scope", scopes.sorted().joinToString(" "))
        }
        val node = try {
            http.post().uri(metadata.tokenEndpoint).contentType(MediaType.APPLICATION_FORM_URLENCODED).accept(MediaType.APPLICATION_JSON)
                .body(form).retrieve().body(JsonNode::class.java)
        } catch (e: RestClientResponseException) {
            val error = runCatching { objectMapper.readTree(e.responseBodyAsString) }.getOrNull()
            // Only the service's own `invalid_client` is a refusal. A bare or HTML 401 comes from
            // whatever stands in front of it (a gateway, a WAF): not an answer about this registration.
            if (error?.str("error") == "invalid_client") {
                val description = error.str("error_description")
                throw SupportRefusedException(listOfNotNull("invalid_client", description).joinToString(": "), serviceTime(e))
            }
            throw unavailable("requesting a token", e)
        } catch (e: RestClientException) {
            throw unavailable("requesting a token", e)
        } ?: throw SupportUnavailableException("The token endpoint answered with nothing")
        return SupportToken(
            accessToken = node.requiredText("access_token"),
            expiresInSeconds = node.path("expires_in").asLong(0).takeIf { it > 0 } ?: DEFAULT_EXPIRES_IN,
            scopes = SupportScope.parseAll(node.str("scope")),
        )
    }

    fun connection(metadata: SupportServiceMetadata, accessToken: String): SupportConnectionInfo {
        val node = call("reading the connection status") {
            http.get().uri("${metadata.apiEndpoint}/connection").accept(MediaType.APPLICATION_JSON)
                .headers { it.setBearerAuth(accessToken) }.retrieve().body(JsonNode::class.java)
        } ?: throw SupportUnavailableException("The support service answered the connection status with nothing")
        return SupportConnectionInfo(
            clientId = node.requiredText("clientId"),
            status = node.requiredText("status"),
            scopes = node.path("scopes").takeIf { it.isArray }?.mapNotNull { scope -> scope.takeIf { it.isString }?.stringValue()?.let(SupportScope::parse) }?.toSet().orEmpty(),
            claimCodeExpiresAt = node.instant("claimCodeExpiresAt"),
            declinedAt = node.instant("declinedAt"),
            pause = node.path("pause").takeIf { it.isObject }?.let { p ->
                // An unreadable pause informs nothing and fails nothing: without a start or a reason it is left out.
                val since = p.instant("since")
                val reason = p.str("reason")
                if (since == null || reason == null) {
                    null
                } else {
                    SupportPause(p.path("scopes").takeIf { it.isArray }?.mapNotNull { s -> s.takeIf { it.isString }?.stringValue()?.let(SupportScope::parse) }?.toSet().orEmpty(), since, p.instant("until"), reason.take(MAX_PAUSE_REASON))
                }
            },
        )
    }

    /** `DELETE {api}/connection`: this installation ends its connection. The service answers `204`. */
    fun disconnect(metadata: SupportServiceMetadata, accessToken: String) {
        call("disconnecting") {
            http.delete().uri("${metadata.apiEndpoint}/connection").headers { it.setBearerAuth(accessToken) }.retrieve().toBodilessEntity()
        }
    }

    /** The answer's `Date` header, or null when it is absent or unreadable. */
    private fun serviceTime(e: RestClientResponseException): Instant? = runCatching { e.responseHeaders?.date }.getOrNull()
        ?.takeIf { it >= 0 }?.let(Instant::ofEpochMilli)

    private fun <T> call(what: String, block: () -> T): T = try {
        block()
    } catch (e: RestClientException) {
        throw unavailable(what, e)
    }

    private fun unavailable(what: String, e: RestClientException): SupportUnavailableException {
        val detail = (e as? RestClientResponseException)?.let { response -> "${response.statusCode.value()} ${errorOf(response)}".trim() } ?: describeNoAnswer(e)
        return SupportUnavailableException("Failed $what: $detail", e)
    }

    private fun errorOf(e: RestClientResponseException): String = runCatching { objectMapper.readTree(e.responseBodyAsString) }.getOrNull()
        ?.let { listOfNotNull(it.str("error"), it.str("error_description")).joinToString(": ") }
        .orEmpty()

    private fun JsonNode.str(field: String): String? = path(field).takeIf { it.isString }?.stringValue()

    /** An RFC 3339 timestamp, or null when absent or unreadable: it only informs, so it never fails a call. */
    private fun JsonNode.instant(field: String): Instant? = str(field)?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    private fun JsonNode.requiredText(field: String): String = str(field)?.takeIf(String::isNotBlank)
        ?: throw SupportUnavailableException("The support service response is missing '$field'")

    private companion object {
        /** Used when a token response leaves out `expires_in`; short, so a token is refreshed soon. */
        const val DEFAULT_EXPIRES_IN = 120L

        /** The service's own limit; a longer reason is cut, not trusted to fit the page. */
        const val MAX_PAUSE_REASON = 200
    }
}

/**
 * Why a call got no usable answer, in words an installation administrator can act on: the HTTP
 * client's own messages ("I/O error on GET request for …: null", "Request cancelled") say neither
 * what happened nor what to check. A failure that is not about reaching the service (an answer that
 * is not JSON, say) keeps its own message.
 *
 * The JDK client wraps an unresolvable host ([UnresolvedAddressException]), a connect timeout
 * ([HttpConnectTimeoutException]) and a host or network it has no route to (a [SocketException] such
 * as [java.net.NoRouteToHostException]) in a [ConnectException] too, so those are told apart first:
 * a mistyped host, or a network that drops or rejects the packets, is not a stopped service.
 */
internal fun describeNoAnswer(e: Throwable): String {
    val causes = generateSequence(e) { it.cause.takeIf { cause -> cause !== it } }.take(MAX_CAUSES).toList()
    val unreachable = causes.firstOrNull { it is SocketException && it !is ConnectException }
    return when {
        causes.any { it is UnknownHostException || it is UnresolvedAddressException } -> "the support service could not be reached (unknown host)"

        causes.any { it is HttpConnectTimeoutException } -> "the support service could not be reached (the connection timed out)"

        // A rejection on the way (no route to the host, an unreachable network): this installation's
        // network or firewall, not a stopped service.
        causes.any { it is ConnectException } && unreachable != null ->
            "the support service could not be reached (${unreachable.message ?: "no route to the host"}: check this installation's network and firewall)"

        causes.any { it is ConnectException } -> "the support service could not be reached (connection refused)"

        causes.any { it is HttpTimeoutException || it is SocketTimeoutException || it is CancellationException } -> "the support service did not answer in time"

        else -> e.message ?: e.javaClass.simpleName
    }
}

private const val MAX_CAUSES = 8

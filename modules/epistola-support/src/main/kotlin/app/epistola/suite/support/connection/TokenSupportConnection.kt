// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.time.EpistolaClock
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.JsonNode
import java.net.URI
import java.util.UUID

/**
 * A support connection the suite signs in to with a token the other side issued (ADR 0053 §1,
 * [SupportConnectionAuth.BEARER_TOKEN]): a partner, typically. No registration and no OAuth: the
 * token is set in configuration or by an installation administrator, kept encrypted in the
 * connection's row, and sent on every call. The paths are those of the published protocol under
 * [endpoint]: `{endpoint}/api/v1/connection` says the connection is active and which services it
 * grants, as the Epistola support service does.
 *
 * What a check learns is stored in the connection's row, so every node reads the same status.
 */
class TokenSupportConnection(
    override val id: UUID,
    /** The other side's base URL, without a trailing slash. */
    val endpoint: URI,
    private val store: SupportConnectionStore,
    private val supportHttp: SupportHttp,
    /** The services selected while no administrator saved a selection: from the configuration. */
    private val configuredServices: List<String>,
) : SupportConnection {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile private var rest: RestClient? = null

    private val api: URI = URI("$endpoint/api/v1")

    override fun status(): SupportConnectionStatus {
        val stored = store.status()
        val state = when (stored?.state) {
            StoredConnectionState.ACTIVE -> SupportConnectionState.Active(stored.clientId.orEmpty(), stored.scopes.mapNotNull(SupportScope::parse).toSet())

            StoredConnectionState.REFUSED -> SupportConnectionState.Refused(stored.clientId.orEmpty(), stored.reason ?: "the token was refused")

            // Not checked yet, or a state only a registration has.
            else -> SupportConnectionState.Unregistered
        }
        return SupportConnectionStatus(state, endpoint, stored?.checkedAt, stored?.lastError)
    }

    override fun hasScope(scope: SupportScope): Boolean {
        val state = status().state as? SupportConnectionState.Active ?: return false
        return isSelected(scope) && scope in state.scopes && status().lastError == null
    }

    override fun isSelected(scope: SupportScope): Boolean = scope.value in (store.services()?.services ?: SupportScope.normalize(configuredServices))

    /** Until the next check: the scope reads as not granted on every node at once. */
    override fun scopeRefused(scope: SupportScope) {
        val stored = store.status() ?: return
        store.saveStatus(stored.copy(scopes = stored.scopes - scope.value))
    }

    override fun apiEndpoint(): URI = api

    /** Telemetry does not go over a token connection (ADR 0053 step 3: requests and usage). */
    override fun otlpEndpoint(): URI? = null

    override fun requestsEndpoint(): URI = URI("$endpoint/api/v2/requests")

    override fun cachedAccessToken(): String? = store.bearerToken()

    override fun accessToken(): String = store.bearerToken() ?: throw SupportUnavailableException("No token is set for support connection $id")

    /**
     * A client for [apiEndpoint] that sends the token on every request and refuses any other origin.
     * A `401` is answered as it is: the token is the other side's to replace, and the next check says so.
     */
    override fun restClient(): RestClient = rest ?: synchronized(this) {
        rest ?: run {
            val origin = SupportServiceMetadata.origin(api)
            val authorizing = ClientHttpRequestInterceptor { request, body, execution ->
                require(SupportServiceMetadata.origin(request.uri) == origin) {
                    "Refusing to send a support request to ${request.uri}: only $origin is support connection $id"
                }
                request.headers.setBearerAuth(accessToken())
                execution.execute(request, body)
            }
            RestClient.builder().baseUrl(api.toString()).requestFactory(supportHttp.requestFactory()).requestInterceptor(authorizing).build().also { rest = it }
        }
    }

    /**
     * Asks the other side how the connection stands (`GET {api}/connection`) and stores it: active
     * with the services it grants, refused when it no longer accepts the token (`401`), or the error
     * when it cannot be reached, which keeps the services that were granted but closes their gates.
     */
    override fun checkNow() {
        val now = EpistolaClock.instant()
        val before = store.status()
        val token = store.bearerToken() ?: run {
            store.saveStatus(StoredSupportStatus(issuer = endpoint.toString(), state = StoredConnectionState.REFUSED, reason = "no token is set", checkedAt = now))
            return
        }
        try {
            val node = restClient().get().uri("/connection").accept(MediaType.APPLICATION_JSON)
                .headers { it.setBearerAuth(token) }.retrieve().body(JsonNode::class.java)
                ?: throw SupportUnavailableException("Support connection $id answered the connection status with nothing")
            val status = node.path("status").stringValue("")
            val scopes = node.path("scopes").takeIf { it.isArray }?.mapNotNull { s -> s.takeIf { it.isString }?.stringValue() }?.toSet().orEmpty()
            store.saveStatus(
                StoredSupportStatus(
                    issuer = endpoint.toString(),
                    clientId = node.path("clientId").stringValue(null),
                    state = if (status == ACTIVE) StoredConnectionState.ACTIVE else StoredConnectionState.REFUSED,
                    scopes = if (status == ACTIVE) scopes else emptySet(),
                    reason = if (status == ACTIVE) null else "the connection is $status",
                    checkedAt = now,
                    scopesCheckedAt = now,
                ),
            )
        } catch (e: RestClientResponseException) {
            if (e.statusCode.value() == 401) {
                store.saveStatus(StoredSupportStatus(issuer = endpoint.toString(), state = StoredConnectionState.REFUSED, reason = "the token was refused", checkedAt = now))
            } else {
                failed(before, now, "Support connection answered ${e.statusCode.value()}")
            }
        } catch (e: Exception) {
            if (!e.isSupportUnavailable()) throw e
            failed(before, now, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun failed(before: StoredSupportStatus?, now: java.time.Instant, error: String) {
        log.info("Support connection {} check failed: {}", id, error)
        store.saveStatus((before ?: StoredSupportStatus(issuer = endpoint.toString())).copy(checkedAt = now, lastError = error))
    }

    private companion object {
        const val ACTIVE = "ACTIVE"
    }
}

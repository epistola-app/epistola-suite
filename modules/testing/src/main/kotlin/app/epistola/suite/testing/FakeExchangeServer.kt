// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A stand-in Exchange over a real loopback HTTP server.
 *
 * Shared test infrastructure rather than a fixture of one module: the same server backs the
 * domain's enrollment and worker tests and the host app's UI tests, which is the only way to prove
 * that what the client raises actually reaches the page an administrator is looking at.
 *
 * Publication is a conversation with a remote service, so the parts worth testing — token refresh,
 * idempotent submission, following a submission to a terminal state, and what Suite does with 401
 * and 403 — only exist across that boundary. A real socket exercises the actual `RestClient`,
 * multipart encoding, and error translation; each response is a `var` so a test can make Exchange
 * behave badly on demand.
 */
class FakeExchangeServer : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    /** State of the most recent authorization request, so a test can complete the redirect flow. */
    val latestState = AtomicReference<String>()

    /** Idempotency keys seen by the submission endpoint, in order. */
    val submittedIdempotencyKeys = mutableListOf<String>()
    val submittedNamespaces = mutableListOf<String>()
    var submittedBytes: Int = 0
        private set

    /**
     * The public product discovery document, in the shape epistola.app actually publishes:
     * `{"version":1,"issuer":…,"baseUrl":…}`.
     */
    var discoveryResponse: () -> Response = { Response(200, """{"version":1,"issuer":"$baseUrl","baseUrl":"$baseUrl"}""") }

    /** Lets a test make the OAuth metadata disagree with the discovered issuer. */
    var oauthMetadataIssuer: String? = null

    var tokenResponse: () -> Response = { Response(200, defaultToken()) }
    var submitResponse: () -> Response = { Response(200, publicationBody(remotePublicationId, "PENDING")) }
    var statusResponse: () -> Response = { Response(200, publicationBody(remotePublicationId, "ACCEPTED")) }

    val remotePublicationId: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000aa")
    var namespaces: List<String> = listOf("public-services")

    /** Lets a test hand back a different organization on a later authorization. */
    var organizationSlug: String = "acme"

    private val connections = AtomicInteger()

    init {
        server.createContext("/.well-known/epistola/exchange.json") { exchange -> exchange.respond(discoveryResponse()) }
        server.createContext("/.well-known/oauth-authorization-server") { exchange ->
            exchange.respond(
                Response(
                    200,
                    """
                    {
                      "issuer": "${oauthMetadataIssuer ?: baseUrl}",
                      "authorization_request_endpoint": "$baseUrl/oauth/authorization-requests",
                      "token_endpoint": "$baseUrl/oauth/token"
                    }
                    """.trimIndent(),
                ),
            )
        }
        server.createContext("/oauth/authorization-requests") { exchange ->
            latestState.set(exchange.form()["state"])
            exchange.respond(Response(200, """{"authorization_uri":"$baseUrl/authorize","expires_in":300}"""))
        }
        server.createContext("/oauth/token") { exchange -> exchange.respond(tokenResponse()) }
        server.createContext("/api/v1/tenant-connection") { exchange ->
            if (exchange.requestMethod == "DELETE") {
                exchange.respond(Response(204, ""))
            } else {
                // Exchange issues one connection identity per enrolled tenant, and the columns are
                // unique, so every enrollment in a test run needs its own.
                val connection = connections.incrementAndGet()
                exchange.respond(
                    Response(
                        200,
                        """
                        {
                          "tenantConnectionId": "${UUID(CONNECTION_ID_HIGH, connection.toLong())}",
                          "tenantConnectionReference": "tc_01HWHVGZT1FCF9Y2CE4XP${"%03d".format(connection)}",
                          "organization": {"slug": "$organizationSlug", "name": "Acme"},
                          "scopes": ["read", "publish"],
                          "namespaces": [${namespaces.joinToString(",") { """{"slug":"$it"}""" }}]
                        }
                        """.trimIndent(),
                    ),
                )
            }
        }
        server.createContext("/api/v1/publication-submissions") { exchange ->
            val isStatusPoll = exchange.requestURI.path.trimEnd('/') != "/api/v1/publication-submissions"
            if (isStatusPoll) {
                exchange.respond(statusResponse())
            } else {
                submittedIdempotencyKeys += exchange.requestHeaders.getFirst("Idempotency-Key").orEmpty()
                val body = exchange.requestBody.readBytes()
                submittedBytes = body.size
                submittedNamespaces += NAMESPACE_PART.find(body.toString(StandardCharsets.ISO_8859_1))?.groupValues?.get(1)?.trim().orEmpty()
                exchange.respond(submitResponse())
            }
        }
        server.start()
    }

    fun defaultToken(
        accessTokenExpiresIn: Long = 3600,
        applicationId: UUID = OAUTH_APPLICATION_ID,
    ): String = """
        {
          "client_id": "$applicationId",
          "client_secret": "application-secret",
          "tenant_connection_id": "${UUID(CONNECTION_ID_HIGH, connections.get().toLong())}",
          "access_token": "access-token-${UUID.randomUUID()}",
          "expires_in": $accessTokenExpiresIn,
          "refresh_token": "refresh-token-${UUID.randomUUID()}",
          "refresh_token_expires_in": 2592000,
          "scope": "read publish"
        }
    """.trimIndent()

    fun publicationBody(id: UUID, state: String, errorCode: String? = null, errorDetail: String? = null): String = """
        {
          "id": "$id",
          "state": "$state",
          "errorCode": ${errorCode?.let { "\"$it\"" } ?: "null"},
          "errorDetail": ${errorDetail?.let { "\"$it\"" } ?: "null"}
        }
    """.trimIndent()

    /** Restores default behaviour and clears recordings between tests sharing one server. */
    fun reset() {
        latestState.set(null)
        submittedIdempotencyKeys.clear()
        submittedNamespaces.clear()
        submittedBytes = 0
        namespaces = listOf("public-services")
        organizationSlug = "acme"
        discoveryResponse = { Response(200, """{"version":1,"issuer":"$baseUrl","baseUrl":"$baseUrl"}""") }
        oauthMetadataIssuer = null
        tokenResponse = { Response(200, defaultToken()) }
        submitResponse = { Response(200, publicationBody(remotePublicationId, "PENDING")) }
        statusResponse = { Response(200, publicationBody(remotePublicationId, "ACCEPTED")) }
    }

    override fun close() = server.stop(0)

    data class Response(val status: Int, val body: String)

    private fun HttpExchange.form(): Map<String, String> = requestBody.bufferedReader().use { it.readText() }
        .split('&').filter(String::isNotBlank).associate { field ->
            val (name, value) = field.split('=', limit = 2)
            URLDecoder.decode(name, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
        }

    private fun HttpExchange.respond(response: Response) {
        // com.sun.net.httpserver drops the response body if the request body was never consumed,
        // which turns a deliberate 401 into an empty one and hides the error the test is asserting.
        runCatching { requestBody.readBytes() }
        val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        if (bytes.isEmpty()) {
            sendResponseHeaders(response.status, -1)
            close()
        } else {
            sendResponseHeaders(response.status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
    }

    companion object {
        val OAUTH_APPLICATION_ID: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
        private const val CONNECTION_ID_HIGH = 0x4000_8000_0000_0000L

        /** Matches the `namespace` form part past whatever headers the client added to it. */
        private val NAMESPACE_PART =
            Regex("""name="namespace"(?:\r?\n[^\r\n]+)*\r?\n\r?\n([^\r\n]*)""")
    }
}

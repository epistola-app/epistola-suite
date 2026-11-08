// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** One request the [FakeSupportServer] received. */
data class FakeSupportRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
    /** The bearer token sent, if any. */
    val bearerToken: String?,
    /** The registration the bearer token belongs to; null without a valid token. */
    val clientId: String?,
    /** The token's scopes that are still granted now; empty without a valid token. */
    val tokenScopes: Set<String>,
) {
    val bodyText: String get() = String(body, StandardCharsets.UTF_8)

    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    fun json(): JsonNode = MAPPER.readTree(bodyText)

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    internal companion object {
        val MAPPER: JsonMapper = JsonMapper.builder().build()
    }
}

/** What a route answers. */
data class FakeSupportResponse(
    val status: Int,
    val body: ByteArray = ByteArray(0),
    val contentType: String = "application/json",
    val headers: Map<String, String> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    companion object {
        fun json(status: Int, json: String, headers: Map<String, String> = emptyMap()) = FakeSupportResponse(status, json.toByteArray(StandardCharsets.UTF_8), "application/json", headers)

        fun empty(status: Int) = FakeSupportResponse(status)

        /** `401` as the protocol answers a missing or invalid token. */
        fun invalidToken() = json(401, """{"error":"invalid_token"}""", mapOf("WWW-Authenticate" to """Bearer error="invalid_token""""))

        /** `403` as the protocol answers a token without [scope]. */
        fun insufficientScope(scope: String) = json(403, """{"error":"insufficient_scope"}""", mapOf("WWW-Authenticate" to """Bearer error="insufficient_scope", scope="$scope""""))
    }
}

/**
 * A failure the [FakeSupportServer] plays instead of its normal answer: see [FakeSupportServer.fault].
 * Each is something a real support service, or what stands in front of it, does when it is down,
 * overloaded or misconfigured.
 */
sealed interface FakeSupportFault {
    /** Answers [response] instead: a `500`, a proxy's HTML `502`, `503` with `Retry-After`, `429`, malformed JSON. */
    data class Respond(val response: FakeSupportResponse) : FakeSupportFault

    /** Reads the request and answers nothing until [FakeSupportServer.releaseHangs], the client gives up, or [maxMillis] passed. */
    data class Hang(val maxMillis: Long = 60_000) : FakeSupportFault

    /**
     * Accepts the connection and never reads the request body, as an overloaded service whose workers
     * are all busy: a large upload blocks once the socket buffers are full. Until
     * [FakeSupportServer.releaseHangs], the client gives up, or [maxMillis] passed.
     */
    data class StallBeforeReadingBody(val maxMillis: Long = 60_000) : FakeSupportFault

    /** Sends `200` headers, then one byte of [body] every [intervalMillis] for at most [totalMillis]: every single read is quick, the whole is not. */
    data class SlowDrip(val intervalMillis: Long, val totalMillis: Long, val body: String = """{"issuer":"x"}""") : FakeSupportFault

    /** Announces [declaredBytes] with `Content-Length`, sends only [sentBytes] of them, then closes the connection. */
    data class TruncatedBody(val status: Int = 200, val declaredBytes: Int = 1_000, val sentBytes: Int = 10) : FakeSupportFault

    /** Streams a JSON document of [bytes] bytes, chunked; [FakeSupportServer.bytesWritten] counts what actually left. */
    data class Oversized(val bytes: Long, val status: Int = 200) : FakeSupportFault

    companion object {
        /** `500` with the service's JSON error. */
        fun serverError() = Respond(FakeSupportResponse.json(500, """{"error":"server_error"}"""))

        /** `502` with an HTML page, as a proxy in front of a dead service answers. */
        fun badGatewayHtml() = Respond(FakeSupportResponse(502, "<html><body><h1>502 Bad Gateway</h1></body></html>".toByteArray(), "text/html"))

        /** `503` with `Retry-After`. */
        fun unavailable(retryAfterSeconds: Int = 120) = Respond(FakeSupportResponse.json(503, """{"error":"temporarily_unavailable"}""", mapOf("Retry-After" to retryAfterSeconds.toString())))

        /** `429` with `Retry-After`. */
        fun tooManyRequests(retryAfterSeconds: Int = 60) = Respond(FakeSupportResponse.json(429, """{"error":"rate_limited"}""", mapOf("Retry-After" to retryAfterSeconds.toString())))

        /** `200` whose JSON body is cut off: unparseable. */
        fun malformedJson() = Respond(FakeSupportResponse.json(200, """{"access_token":"tok","expires_in":"""))

        /** `401` once: answer it [times] times with `invalid_token`, then normally. */
        fun unauthorized() = Respond(FakeSupportResponse.invalidToken())
    }
}

/**
 * A stand-in Epistola support service (`docs/support-protocol.md`) over a real loopback HTTP
 * server: OAuth metadata, registration (RFC 7591), the token endpoint with `private_key_jwt`
 * (RFC 7523) — which verifies each ES256 assertion against the key registered — and
 * `GET /api/v1/connection`.
 *
 * Shared test infrastructure: other modules add the service endpoints they need with [route]
 * from their own tests, for example
 *
 * ```kotlin
 * support.route("PUT", "/api/v2/requests/") { request ->
 *     if ("requests" !in request.tokenScopes) FakeSupportResponse.insufficientScope("requests")
 *     else FakeSupportResponse.json(201, """{"requestId":"0193","url":null}""")
 * }
 * ```
 *
 * and read [requests] to assert what arrived. Registrations start PENDING; [activate] activates one,
 * [revoke] makes the token endpoint refuse it.
 *
 * Failures: [fault] plays a [FakeSupportFault] on matching requests (a hang, a slow drip, a `5xx`, a
 * truncated or oversized body, ...), [stop] refuses connections until [start]. Requests are served on
 * a thread each, so one hanging request does not hold up the others.
 */
class FakeSupportServer : AutoCloseable {
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable -> Thread(runnable, "fake-support-server").apply { isDaemon = true } }

    @Volatile private var server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    private val port: Int = server.address.port

    @Volatile private var running = false

    val baseUrl: String get() = "http://127.0.0.1:$port"

    val tokenEndpoint: String get() = "${endpointBase()}/oauth2/token"
    val registrationEndpoint: String get() = "${endpointBase()}/oauth2/register"
    val apiEndpoint: String get() = "${endpointBase()}/api/v1"
    val otlpEndpoint: String get() = "${endpointBase()}/otlp"

    /** Support requests, protocol v2: `epistola_requests_endpoint`. */
    val requestsEndpoint: String get() = "${endpointBase()}/api/v2/requests"

    /** A registration this service holds. */
    data class Registration(
        val clientId: String,
        val installationId: String?,
        val publicJwk: String,
        val thumbprint: String,
        @Volatile var claimCode: String,
        /** When the service deletes this registration if nobody activates it: seven days after the code was issued. */
        @Volatile var claimCodeExpiresAt: Instant,
        @Volatile var status: String = "PENDING",
        @Volatile var scopes: Set<String> = emptySet(),
        @Volatile var declinedAt: Instant? = null,
        /** Services paused now, with the reason and the end (null: until resumed); empty when none. */
        @Volatile var pausedScopes: Set<String> = emptySet(),
        @Volatile var pauseReason: String = "",
        @Volatile var pausedSince: Instant? = null,
        @Volatile var pausedUntil: Instant? = null,
    )

    /** Every request, in order. */
    val requests: MutableList<FakeSupportRequest> = CopyOnWriteArrayList()

    /** The body of every registration request, in order. */
    val registrationRequests: MutableList<JsonNode> = CopyOnWriteArrayList()

    /** Registrations by client id. */
    val registrations: MutableMap<String, Registration> = Collections.synchronizedMap(linkedMapOf())

    /** Every token issued, with what it grants. */
    val issuedTokens: MutableMap<String, Pair<String, Set<String>>> = Collections.synchronizedMap(linkedMapOf())

    /** How many token requests were answered 200. */
    val tokensIssued: Int get() = issuedTokens.size

    /** Every bearer token seen, in order, per request path. */
    val bearerTokensSeen: List<Pair<String, String>> get() = requests.mapNotNull { r -> r.bearerToken?.let { r.path to it } }

    /** `expires_in` of issued tokens. */
    @Volatile var tokenLifetimeSeconds: Long = 600

    /** "Now" for claim code expiry; a test sets it to its own clock. */
    @Volatile var now: () -> Instant = { Instant.now() }

    /**
     * Checks a client assertion's `iat` and `exp` like the service: against this server's clock, with
     * 60 seconds of skew. That clock is the real one, as the JDK server always sends the real time as
     * its `Date` header (it overrides one set by a handler), so a test sets its own clock far from it
     * to play a node whose clock is off. Off by default: tests run on a fixed clock.
     */
    @Volatile var checksAssertionTimes: Boolean = false

    /** How long a claim code lives before the service deletes a registration nobody activated. */
    @Volatile var claimCodeLifetime: Duration = Duration.ofDays(7)

    /** Overrides the metadata `issuer`, to make it disagree with the URL asked. */
    @Volatile var metadataIssuer: String? = null

    /** Overrides where the metadata says the endpoints are, to put them off the issuer's origin. */
    @Volatile var endpointBaseUrl: String? = null

    /** Overrides the registration answer, e.g. `{ FakeSupportResponse.json(429, "{}") }`. */
    @Volatile var registrationResponse: ((FakeSupportRequest) -> FakeSupportResponse)? = null

    /** Overrides the token answer, e.g. a `503`; null answers as the service would. */
    @Volatile var tokenResponse: ((FakeSupportRequest) -> FakeSupportResponse?)? = null

    /** `scopes_supported` in the metadata: the services this service offers; null leaves it out. */
    @Volatile var scopesSupported: List<String>? = listOf("telemetry", "requests", "mirror", "usage")

    /**
     * Behave like a support service from before unknown scope names were ignored: a token request
     * naming a scope outside [scopesSupported] is `400 invalid_scope`, and an empty `scope=` counts
     * as absent (every granted scope).
     */
    @Volatile var olderScopeHandling: Boolean = false

    /** The `scope` parameter of every token request, in order; null where it was absent. */
    val tokenScopeRequests: MutableList<String?> = CopyOnWriteArrayList()

    private val routes = CopyOnWriteArrayList<Triple<String, String, (FakeSupportRequest) -> FakeSupportResponse>>()
    private val seenJtis = Collections.synchronizedSet(mutableSetOf<String>())
    private val counter = AtomicInteger()

    private class ActiveFault(val method: String?, val pathPrefix: String, val fault: FakeSupportFault, val remaining: AtomicInteger)

    private val faults = CopyOnWriteArrayList<ActiveFault>()

    @Volatile private var hangs = CountDownLatch(1)

    /** The server created with this instance has not been started yet; after a [stop], a new one binds the same port. */
    private var fresh = true

    /** Response body bytes written by fault answers ([FakeSupportFault.Oversized], [FakeSupportFault.SlowDrip]) before the client stopped reading. */
    val bytesWritten = AtomicLong()

    /** Requests a fault answered, in order. */
    val faulted: MutableList<FakeSupportRequest> = CopyOnWriteArrayList()

    init {
        start()
    }

    /**
     * Plays [fault] on [method] requests (any method when null) whose path starts with [pathPrefix],
     * the next [times] times, before routes and built-in endpoints. The fault added last wins.
     */
    fun fault(fault: FakeSupportFault, method: String? = null, pathPrefix: String = "/", times: Int = Int.MAX_VALUE) {
        faults += ActiveFault(method?.uppercase(), pathPrefix, fault, AtomicInteger(times))
    }

    /** Drops every fault and lets hanging requests go: the service is healthy again. */
    fun clearFaults() {
        faults.clear()
        releaseHangs()
    }

    /** Lets every request held by [FakeSupportFault.Hang] or [FakeSupportFault.StallBeforeReadingBody] go. */
    fun releaseHangs() {
        hangs.countDown()
        hangs = CountDownLatch(1)
    }

    /** Stops listening: connections are refused, as when the service is down, until [start]. */
    fun stop() {
        if (!running) return
        running = false
        releaseHangs()
        server.stop(0)
    }

    /** Listens again on the same port after [stop]. */
    fun start() {
        if (running) return
        if (!fresh) server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        fresh = false
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                if (!playFault(exchange)) exchange.respond(dispatch(exchange))
            } catch (e: IOException) {
                // The client went away mid-answer (a timeout or a size cap closed it).
                runCatching { exchange.close() }
            } catch (e: Exception) {
                runCatching { exchange.respond(FakeSupportResponse.json(500, """{"error":"server_error","error_description":"${e.message?.replace("\"", "'")}"}""")) }
            }
        }
        server.start()
        running = true
    }

    /**
     * Answers [method] requests whose path starts with [pathPrefix] with [handler]. Checked before
     * the built-in endpoints; the route added last wins when several match.
     */
    fun route(method: String, pathPrefix: String, handler: (FakeSupportRequest) -> FakeSupportResponse) {
        routes += Triple(method.uppercase(), pathPrefix, handler)
    }

    /** Drops every [route]: only the built-in endpoints answer again. */
    fun clearRoutes() {
        routes.clear()
    }

    /** The registration made last. */
    val latestRegistration: Registration get() = registrations.values.last()

    /** Activates [clientId] and grants [scopes], as staff do in the backoffice. */
    fun activate(
        clientId: String = latestRegistration.clientId,
        scopes: Set<String> = setOf("telemetry", "requests", "mirror"),
    ) {
        registrations.getValue(clientId).apply {
            status = "ACTIVE"
            this.scopes = scopes
        }
    }

    /** Declines [clientId]: it still gets tokens without scope, and reads DECLINED. */
    fun decline(clientId: String = latestRegistration.clientId) {
        registrations.getValue(clientId).apply {
            status = "DECLINED"
            declinedAt = now().truncatedTo(ChronoUnit.SECONDS)
        }
    }

    /** Pauses [scopes] of [clientId] with [reason] until [until] (null: until [resume]); left out of new tokens. */
    fun pause(scopes: Set<String>, reason: String, until: Instant? = null, clientId: String = latestRegistration.clientId) {
        registrations.getValue(clientId).apply {
            pausedScopes = scopes
            pauseReason = reason
            pausedSince = now().truncatedTo(ChronoUnit.SECONDS)
            pausedUntil = until
        }
    }

    fun resume(clientId: String = latestRegistration.clientId) {
        registrations.getValue(clientId).pausedScopes = emptySet()
    }

    /** Revokes [clientId]: its tokens stop working and the token endpoint refuses it. */
    fun revoke(clientId: String = latestRegistration.clientId) {
        registrations.getValue(clientId).status = "REVOKED"
        issuedTokens.entries.removeIf { it.value.first == clientId }
    }

    /**
     * Deletes [clientId] as the service does once its claim code expired unlinked (or staff removed
     * it): the registration and its tokens are gone, so the token endpoint answers `invalid_client`
     * (unknown client).
     */
    fun expire(clientId: String = latestRegistration.clientId) {
        registrations.remove(clientId)
        issuedTokens.entries.removeIf { it.value.first == clientId }
    }

    /** Forgets every issued token, so the next request with one is answered 401. */
    fun expireTokens() {
        issuedTokens.clear()
    }

    override fun close() {
        stop()
        executor.shutdownNow()
    }

    /** Plays the first matching fault, if any; true when it answered (or deliberately did not). */
    private fun playFault(exchange: HttpExchange): Boolean {
        val method = exchange.requestMethod.uppercase()
        val path = exchange.requestURI.path
        val active = faults.lastOrNull { (it.method == null || it.method == method) && path.startsWith(it.pathPrefix) && it.remaining.get() > 0 } ?: return false
        if (active.remaining.decrementAndGet() < 0) return false
        val fault = active.fault
        if (fault is FakeSupportFault.StallBeforeReadingBody) {
            faulted += FakeSupportRequest(method, path, emptyMap(), exchange.requestHeaders.mapValues { it.value.toList() }, ByteArray(0), null, null, emptySet())
            hangs.await(fault.maxMillis, TimeUnit.MILLISECONDS)
            exchange.close()
            return true
        }
        val request = read(exchange)
        requests += request
        faulted += request
        when (fault) {
            is FakeSupportFault.Respond -> exchange.respond(fault.response)

            is FakeSupportFault.Hang -> {
                hangs.await(fault.maxMillis, TimeUnit.MILLISECONDS)
                exchange.close()
            }

            is FakeSupportFault.SlowDrip -> {
                exchange.responseHeaders.add("Content-Type", "application/json")
                val bytes = fault.body.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(fault.totalMillis)
                exchange.responseBody.use { out ->
                    var i = 0
                    while (i < bytes.size && System.nanoTime() < until) {
                        out.write(bytes[i++].toInt())
                        out.flush()
                        bytesWritten.incrementAndGet()
                        Thread.sleep(fault.intervalMillis)
                    }
                }
                exchange.close()
            }

            is FakeSupportFault.TruncatedBody -> {
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(fault.status, fault.declaredBytes.toLong())
                exchange.responseBody.write("{\"x\":\"".toByteArray() + ByteArray((fault.sentBytes - 6).coerceAtLeast(0)) { 'a'.code.toByte() })
                exchange.responseBody.flush()
                // Closed under the declared length: the client sees a premature end of the body.
                exchange.close()
            }

            is FakeSupportFault.Oversized -> {
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(fault.status, 0)
                exchange.responseBody.use { out ->
                    val head = """{"issuer":"$baseUrl","x":"""".toByteArray()
                    out.write(head)
                    bytesWritten.addAndGet(head.size.toLong())
                    val chunk = ByteArray(64 * 1024) { 'a'.code.toByte() }
                    var left = fault.bytes - head.size - 2
                    while (left > 0) {
                        val n = minOf(left, chunk.size.toLong()).toInt()
                        out.write(chunk, 0, n)
                        bytesWritten.addAndGet(n.toLong())
                        left -= n
                    }
                    out.write("\"}".toByteArray())
                }
                exchange.close()
            }

            is FakeSupportFault.StallBeforeReadingBody -> Unit
        }
        return true
    }

    private fun endpointBase(): String = endpointBaseUrl ?: baseUrl

    private fun dispatch(exchange: HttpExchange): FakeSupportResponse {
        val request = read(exchange)
        requests += request
        routes.lastOrNull { (method, prefix) -> method == request.method && request.path.startsWith(prefix) }?.let { return it.third(request) }
        return when {
            request.method == "GET" && request.path == "/.well-known/oauth-authorization-server" -> metadata()
            request.method == "POST" && request.path == "/oauth2/register" -> registrationResponse?.invoke(request) ?: register(request)
            request.method == "POST" && request.path == "/oauth2/token" -> tokenResponse?.invoke(request) ?: token(request)
            request.method == "GET" && request.path == "/api/v1/connection" -> connection(request)
            request.method == "DELETE" && request.path == "/api/v1/connection" -> disconnect(request)
            request.method == "PUT" && request.path.startsWith(USAGE_HOURS) -> usageHour(request)
            else -> FakeSupportResponse.json(404, """{"error":"not_found"}""")
        }
    }

    private fun metadata(): FakeSupportResponse = FakeSupportResponse.json(
        200,
        """
        {
          "issuer": "${metadataIssuer ?: baseUrl}",
          "token_endpoint": "$tokenEndpoint",
          "registration_endpoint": "$registrationEndpoint",
          "jwks_uri": "${endpointBase()}/oauth2/jwks",
          "grant_types_supported": ["client_credentials"],
          "token_endpoint_auth_methods_supported": ["private_key_jwt"],
          "token_endpoint_auth_signing_alg_values_supported": ["ES256"],
          ${scopesSupported?.let { list -> "\"scopes_supported\": [${list.joinToString(",") { "\"$it\"" }}]," }.orEmpty()}
          "epistola_api_endpoint": "$apiEndpoint",
          "epistola_otlp_endpoint": "$otlpEndpoint",
          "epistola_requests_endpoint": "$requestsEndpoint"
        }
        """.trimIndent(),
    )

    private fun register(request: FakeSupportRequest): FakeSupportResponse {
        val body = request.json()
        registrationRequests.add(body)
        val keys = body.path("jwks").path("keys")
        if (!keys.isArray || keys.size() != 1) return invalidMetadata("jwks must hold exactly one key")
        val jwk = runCatching { JWK.parse(keys[0].toString()) }.getOrNull() as? ECKey ?: return invalidMetadata("not an EC key")
        if (jwk.isPrivate) return invalidMetadata("the key has private parts")
        if (jwk.keyID.isNullOrBlank()) return invalidMetadata("kid is required")
        val thumbprint = jwk.computeThumbprint().toString()
        val installationId = body.path("epistola_installation_id").takeIf { it.isString }?.stringValue()
        val existing = registrations.values.firstOrNull { it.installationId == installationId && it.thumbprint == thumbprint }
        val expiresAt = now().plus(claimCodeLifetime).truncatedTo(ChronoUnit.SECONDS)
        val registration = if (existing != null) {
            existing.claimCode = claimCode()
            existing.claimCodeExpiresAt = expiresAt
            existing
        } else {
            // As long as the support service's (sc_ and 26 characters), so pages are tested with ids of the real width.
            val clientId = "sc_test%022d".format(counter.incrementAndGet())
            Registration(clientId, installationId, jwk.toJSONString(), thumbprint, claimCode(), expiresAt).also { registrations[clientId] = it }
        }
        return FakeSupportResponse.json(
            if (existing != null) 200 else 201,
            """
            {
              "client_id": "${registration.clientId}",
              "client_id_issued_at": 1730800000,
              "client_name": ${body.path("client_name")},
              "grant_types": ["client_credentials"],
              "token_endpoint_auth_method": "private_key_jwt",
              "jwks": ${body.path("jwks")},
              "epistola_claim_code": "${registration.claimCode}",
              "epistola_claim_code_expires_at": "${registration.claimCodeExpiresAt}",
              "epistola_status": "${if (registration.status == "ACTIVE") "ACTIVE" else "PENDING"}"
            }
            """.trimIndent(),
        )
    }

    private fun token(request: FakeSupportRequest): FakeSupportResponse {
        val form = request.bodyText.split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), StandardCharsets.UTF_8)
        }
        if (form["grant_type"] != "client_credentials") return FakeSupportResponse.json(400, """{"error":"unsupported_grant_type"}""")
        if (form["client_assertion_type"] != "urn:ietf:params:oauth:client-assertion-type:jwt-bearer") return invalidClient("not a jwt-bearer assertion")
        val clientId = form["client_id"] ?: return invalidClient("no client_id")
        val registration = registrations[clientId] ?: return invalidClient("unknown client")
        if (registration.status == "REVOKED") return invalidClient("revoked")
        if (registration.status == "DISCONNECTED") return invalidClient("the installation disconnected")
        val jwt = runCatching { SignedJWT.parse(form["client_assertion"]) }.getOrNull() ?: return invalidClient("unreadable assertion")
        val key = ECKey.parse(registration.publicJwk)
        if (jwt.header.algorithm.name != "ES256" || jwt.header.keyID != key.keyID) return invalidClient("wrong alg or kid")
        if (!jwt.verify(ECDSAVerifier(key))) return invalidClient("bad signature")
        val claims = jwt.jwtClaimsSet
        if (claims.issuer != clientId || claims.subject != clientId) return invalidClient("iss and sub must be the client id")
        if (claims.audience.none { it == tokenEndpoint || it == baseUrl }) return invalidClient("wrong audience ${claims.audience}")
        val iat = claims.issueTime ?: return invalidClient("no iat")
        val exp = claims.expirationTime ?: return invalidClient("no exp")
        if (exp.time - iat.time > 300_000) return invalidClient("assertion lives longer than five minutes")
        if (checksAssertionTimes) {
            val serviceNow = Instant.now()
            if (iat.toInstant().isAfter(serviceNow.plus(ASSERTION_SKEW))) return invalidClient("assertion iat is in the future")
            if (!serviceNow.isBefore(exp.toInstant().plus(ASSERTION_SKEW))) return invalidClient("assertion has expired")
        }
        val jti = claims.jwtid?.takeIf { it.length <= 128 } ?: return invalidClient("no jti")
        if (!seenJtis.add(jti)) return invalidClient("replayed assertion")
        tokenScopeRequests += form["scope"]
        var requested = form["scope"]?.split(' ')?.filter(String::isNotBlank)?.toSet()
        if (olderScopeHandling) {
            if (requested?.any { it !in scopesSupported.orEmpty() } == true) return FakeSupportResponse.json(400, """{"error":"invalid_scope"}""")
            if (requested.isNullOrEmpty()) requested = null
        }
        // Unknown names are ignored, empty asks for none, absent for every granted scope.
        val paused = registration.pausedScopes.takeIf { registration.pausedUntil == null || now().isBefore(registration.pausedUntil) }.orEmpty()
        val granted = if (registration.status == "ACTIVE") (registration.scopes - paused).filter { requested == null || it in requested }.toSet() else emptySet()
        val token = "tok-${counter.incrementAndGet()}"
        issuedTokens[token] = clientId to granted
        return FakeSupportResponse.json(
            200,
            """{"access_token":"$token","token_type":"Bearer","expires_in":$tokenLifetimeSeconds,"scope":"${granted.joinToString(" ")}"}""",
        )
    }

    /** The installation ends its connection: DISCONNECTED, its tokens stop working, `204`. */
    private fun disconnect(request: FakeSupportRequest): FakeSupportResponse {
        val clientId = request.clientId ?: return FakeSupportResponse.invalidToken()
        val registration = registrations[clientId]?.takeIf { it.status == "PENDING" || it.status == "ACTIVE" } ?: return FakeSupportResponse.invalidToken()
        registration.status = "DISCONNECTED"
        issuedTokens.entries.removeIf { it.value.first == clientId }
        return FakeSupportResponse.empty(204)
    }

    /** Usage hours as reported (ADR 0052 §6), by the hour's start; the last report of an hour replaces the one before. */
    val usageReports: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

    private fun usageHour(request: FakeSupportRequest): FakeSupportResponse {
        if (request.clientId == null) return FakeSupportResponse.json(401, """{"error":"invalid_token"}""", mapOf("WWW-Authenticate" to """Bearer error="invalid_token""""))
        if ("usage" !in request.tokenScopes) return FakeSupportResponse.insufficientScope("usage")
        val hour = request.path.removePrefix(USAGE_HOURS)
        val tenants = FakeSupportRequest.MAPPER.readTree(request.bodyText).path("tenants").size()
        usageReports[hour] = request.bodyText
        return FakeSupportResponse.json(200, """{"hour":"$hour","tenants":$tenants}""")
    }

    private fun connection(request: FakeSupportRequest): FakeSupportResponse {
        val clientId = request.clientId ?: return FakeSupportResponse.invalidToken()
        val registration = registrations[clientId] ?: return FakeSupportResponse.invalidToken()
        val paused = registration.pausedScopes.takeIf { it.isNotEmpty() && (registration.pausedUntil == null || now().isBefore(registration.pausedUntil)) }
        val pause = paused?.let {
            """{"scopes": [${it.joinToString(",") { s -> "\"$s\"" }}], "since": "${registration.pausedSince}", "until": ${registration.pausedUntil?.let { u -> "\"$u\"" } ?: "null"}, "reason": ${FakeSupportRequest.MAPPER.writeValueAsString(registration.pauseReason)}}"""
        } ?: "null"
        return FakeSupportResponse.json(
            200,
            """
            {
              "clientId": "$clientId",
              "installationId": "${registration.installationId}",
              "status": "${registration.status}",
              "scopes": [${registration.scopes.joinToString(",") { "\"$it\"" }}],
              "claimCodeExpiresAt": ${if (registration.status == "PENDING") "\"${registration.claimCodeExpiresAt}\"" else "null"},
              "activatedAt": ${if (registration.status == "ACTIVE") "\"2026-11-05T14:02:11Z\"" else "null"},
              "declinedAt": ${registration.declinedAt?.let { "\"$it\"" } ?: "null"},
              "pause": $pause
            }
            """.trimIndent(),
        )
    }

    private fun read(exchange: HttpExchange): FakeSupportRequest {
        val bearer = exchange.requestHeaders.getFirst("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
        val grant = bearer?.let { issuedTokens[it] }
        return FakeSupportRequest(
            method = exchange.requestMethod.uppercase(),
            path = exchange.requestURI.path,
            query = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.contains('=') }.associate {
                URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), StandardCharsets.UTF_8)
            },
            headers = exchange.requestHeaders.mapValues { it.value.toList() },
            body = exchange.requestBody.readAllBytes(),
            bearerToken = bearer,
            clientId = grant?.first,
            // Like the service: a scope staff withdrew stops working before the token expires.
            tokenScopes = grant?.let { (clientId, scopes) -> registrations[clientId]?.takeIf { it.status == "ACTIVE" }?.let { scopes.intersect(it.scopes) } }.orEmpty(),
        )
    }

    private fun HttpExchange.respond(response: FakeSupportResponse) {
        response.headers.forEach { (name, value) -> responseHeaders.add(name, value) }
        if (response.body.isNotEmpty()) responseHeaders.add("Content-Type", response.contentType)
        sendResponseHeaders(response.status, if (response.body.isEmpty()) -1 else response.body.size.toLong())
        if (response.body.isNotEmpty()) responseBody.use { it.write(response.body) }
        close()
    }

    private fun claimCode(): String {
        val alphabet = "23456789ABCDEFGHJKMNPQRSTVWXYZ"
        val n = counter.incrementAndGet()
        val chars = (0 until 8).map { alphabet[(n * 7 + it * 13) % alphabet.length] }.joinToString("")
        return "${chars.take(4)}-${chars.drop(4)}"
    }

    private fun invalidMetadata(reason: String) = FakeSupportResponse.json(400, """{"error":"invalid_client_metadata","error_description":"$reason"}""")

    private fun invalidClient(reason: String) = FakeSupportResponse.json(401, """{"error":"invalid_client","error_description":"$reason"}""")

    private companion object {
        /** The clock skew the service allows a client assertion. */
        val ASSERTION_SKEW: Duration = Duration.ofSeconds(60)

        const val USAGE_HOURS = "/api/v1/usage/hours/"
    }
}

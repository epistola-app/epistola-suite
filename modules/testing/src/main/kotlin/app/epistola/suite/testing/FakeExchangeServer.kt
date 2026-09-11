// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
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

    /** Makes this Exchange refuse an application or connection a Suite still holds, as a rebuilt one would. */
    var rejectCarriedIdentity: Boolean = false

    /** Lets a test make the OAuth metadata disagree with the discovered issuer. */
    var oauthMetadataIssuer: String? = null

    /**
     * Where the OAuth metadata says its endpoints live, when that is not this server.
     *
     * A discovery document names the endpoints, and the token endpoint receives the client
     * secret and refresh token — so a poisoned one pointing elsewhere is the case worth being
     * able to reproduce.
     */
    var oauthEndpointBaseUrl: String? = null

    var tokenResponse: () -> Response = { Response(200, defaultToken()) }
    var submitResponse: () -> Response = { Response(200, publicationBody(remotePublicationId, "QUEUED")) }
    var statusResponse: () -> Response = { Response(200, publicationBody(remotePublicationId, "ACCEPTED")) }

    val remotePublicationId: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000aa")
    var namespaces: List<String> = listOf("public-services")

    /** Lets a test hand back a different organization on a later authorization. */
    var organizationSlug: String = "acme"

    /** Catalogs this Exchange hosts, keyed `namespace/catalogKey`. Populated by [publish]. */
    val hostedCatalogs = linkedMapOf<String, FakeCatalog>()

    /** Coordinates asked about, in order, so a test can assert how many calls a check actually made. */
    val catalogLookups = mutableListOf<String>()

    /** `namespace/catalogKey@version` for each archive served. */
    val archiveDownloads = mutableListOf<String>()

    /** Search terms received, in order. */
    val searchQueries = mutableListOf<String?>()

    /** Overrides the catalog-detail response, for refusals a hosted catalog cannot express. */
    var catalogResponse: ((FakeCatalog?) -> Response)? = null

    /** Overrides the archive response — a truncated body, a lying Content-Length, a 404. */
    var archiveResponse: ((FakeRelease?) -> Response)? = null

    /** When set, search answers with a `links.next` carrying this cursor, as a paged Exchange does. */
    var nextSearchCursor: String? = null

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
                      "authorization_request_endpoint": "${oauthEndpointBaseUrl ?: baseUrl}/oauth/authorization-requests",
                      "token_endpoint": "${oauthEndpointBaseUrl ?: baseUrl}/oauth/token"
                    }
                    """.trimIndent(),
                ),
            )
        }
        server.createContext("/oauth/authorization-requests") { exchange ->
            val form = exchange.form()
            latestState.set(form["state"])
            // A rebuilt Exchange knows nothing of the application or connection a Suite still holds,
            // and answers a request carrying them as the stale client request it is.
            val carriesIdentity = form["application_id"] != null || form["tenant_connection_id"] != null
            if (rejectCarriedIdentity && carriesIdentity) {
                exchange.respond(Response(400, """{"detail":"unknown_client_identity: Unknown OAuth application"}"""))
            } else {
                exchange.respond(Response(200, """{"authorization_uri":"$baseUrl/authorize","expires_in":300}"""))
            }
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
                          "tenantName": "Acme tenant",
                          "organization": {"slug": "$organizationSlug", "name": "Acme"},
                          "scopes": ["READ", "PUBLISH"],
                          "namespaces": [${namespaces.joinToString(",") { """{"slug":"$it","name":"$it"}""" }}]
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
        server.createContext("/api/v1/catalogs") { exchange ->
            val query = exchange.queryParam("q")
            searchQueries += query
            val matches = hostedCatalogs.values.filter { catalog ->
                query.isNullOrBlank() ||
                    catalog.name.contains(query, ignoreCase = true) ||
                    catalog.catalogKey.contains(query, ignoreCase = true)
            }
            exchange.respond(Response(200, catalogPage(matches)))
        }
        server.createContext("/api/v1/namespaces") { exchange ->
            val segments = exchange.requestURI.path.trim('/').split('/')
            // /api/v1/namespaces/{ns}/catalogs/{key}[/releases[/{version}/archive]]
            val namespace = segments.getOrNull(3)
            val catalogKey = segments.getOrNull(5)
            val catalog = hostedCatalogs["$namespace/$catalogKey"]
            when {
                segments.size == 6 -> {
                    catalogLookups += "$namespace/$catalogKey"
                    catalogResponse?.let { exchange.respond(it(catalog)) }
                        ?: exchange.respond(
                            catalog?.let { Response(200, catalogSummary(it)) }
                                ?: Response(404, PROBLEM_NOT_FOUND),
                        )
                }
                segments.size == 7 && segments[6] == "releases" ->
                    exchange.respond(
                        catalog?.let { Response(200, releasePage(it)) } ?: Response(404, PROBLEM_NOT_FOUND),
                    )
                segments.size == 9 && segments[6] == "releases" && segments[8] == "archive" -> {
                    val version = segments[7]
                    val release = catalog?.releases?.firstOrNull { it.version == version }
                    archiveResponse?.let { exchange.respond(it(release)) } ?: when {
                        release == null -> exchange.respond(Response(404, PROBLEM_NOT_FOUND))
                        else -> {
                            archiveDownloads += "$namespace/$catalogKey@$version"
                            exchange.respondBytes(200, "application/zip", release.archive, mapOf("ETag" to "\"${release.sha256}\""))
                        }
                    }
                }
                else -> exchange.respond(Response(404, PROBLEM_NOT_FOUND))
            }
        }
        server.start()
    }

    /**
     * Hosts [archive] as `namespace/catalogKey` at [version], creating the catalog on first call.
     *
     * Releases are kept in the order published, which is also how Exchange orders them — by
     * publication, not by SemVer — so a test can reproduce the case where those two disagree.
     */
    fun publish(
        namespace: String,
        catalogKey: String,
        archive: ByteArray,
        version: String,
        name: String = catalogKey,
        availability: String = "AVAILABLE",
        scanState: String = "CLEAN",
    ) {
        val key = "$namespace/$catalogKey"
        val existing = hostedCatalogs[key]
            ?: FakeCatalog(namespace, catalogKey, name).also { hostedCatalogs[key] = it }
        hostedCatalogs[key] = existing.copy(
            releases = existing.releases + FakeRelease(version, archive, availability, scanState),
        )
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

    /**
     * `namespace`, `createdAt` and `updatedAt` are required by the contract even though Suite reads
     * none of them, so they are emitted: a fake that answers with less than Exchange does is a fake
     * that lets a client ship broken. The timestamps are fixed rather than current because nothing
     * asserts on them and a moving value in a fixture is a flake waiting to happen.
     */
    fun publicationBody(
        id: UUID,
        state: String,
        errorCode: String? = null,
        errorDetail: String? = null,
        namespace: String = "acme",
    ): String = """
        {
          "id": "$id",
          "namespace": "$namespace",
          "state": "$state",
          "createdAt": "$FIXED_TIMESTAMP",
          "updatedAt": "$FIXED_TIMESTAMP",
          "errorCode": ${errorCode?.let { "\"$it\"" } ?: "null"},
          "errorDetail": ${errorDetail?.let { "\"$it\"" } ?: "null"}
        }
    """.trimIndent()

    /**
     * Every field the contract marks required is emitted, including ones Suite never reads, for the
     * reason the publication body already states: a fake that answers with less than Exchange does
     * is a fake that lets a broken client ship.
     */
    private fun catalogSummary(catalog: FakeCatalog): String = """
        {
          "namespace": "${catalog.namespace}",
          "key": "${catalog.catalogKey}",
          "name": "${catalog.name}",
          "description": ${catalog.description?.let { "\"$it\"" } ?: "null"},
          "ownerOrganizationId": "$OWNER_ORGANIZATION_ID",
          "ownerOrganizationName": "Acme",
          "visibility": "${catalog.visibility}",
          "latestVersion": ${catalog.latestAvailableVersion?.let { "\"$it\"" } ?: "null"}
        }
    """.trimIndent()

    private fun catalogPage(catalogs: Collection<FakeCatalog>): String {
        // Exchange returns the next page as a full URL, not a bare cursor, so the fake does too -
        // that difference is the whole reason the client has to pull the cursor back out.
        val next = nextSearchCursor?.let { ""","next":"$baseUrl/api/v1/catalogs?limit=50&cursor=$it"""" }.orEmpty()
        return """
            {
              "items": [${catalogs.joinToString(",") { catalogSummary(it) }}],
              "links": {"self": "$baseUrl/api/v1/catalogs"$next}
            }
        """.trimIndent()
    }

    private fun releasePage(catalog: FakeCatalog): String = """
        {
          "items": [${catalog.releases.reversed().joinToString(",") { releaseBody(catalog, it) }}],
          "links": {"self": "$baseUrl/api/v1/namespaces/${catalog.namespace}/catalogs/${catalog.catalogKey}/releases"}
        }
    """.trimIndent()

    private fun releaseBody(catalog: FakeCatalog, release: FakeRelease): String = """
        {
          "namespace": "${catalog.namespace}",
          "catalogKey": "${catalog.catalogKey}",
          "version": "${release.version}",
          "sha256": "${release.sha256}",
          "size": ${release.archive.size},
          "scanState": "${release.scanState}",
          "availability": "${release.availability}",
          "publishedAt": "$FIXED_TIMESTAMP",
          "fingerprint": null
        }
    """.trimIndent()

    /** A catalog this Exchange hosts. */
    data class FakeCatalog(
        val namespace: String,
        val catalogKey: String,
        val name: String,
        val description: String? = null,
        val visibility: String = "PUBLIC",
        val releases: List<FakeRelease> = emptyList(),
    ) {
        /**
         * What Exchange computes for `latestVersion`: the newest release anyone may install, so
         * blocked and withdrawn ones are already excluded before a consumer ever sees them.
         */
        val latestAvailableVersion: String? get() = releases.lastOrNull { it.availability == "AVAILABLE" }?.version
    }

    class FakeRelease(
        val version: String,
        val archive: ByteArray,
        val availability: String = "AVAILABLE",
        val scanState: String = "CLEAN",
    ) {
        val sha256: String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive))
    }

    /** Restores default behaviour and clears recordings between tests sharing one server. */
    fun reset() {
        latestState.set(null)
        submittedIdempotencyKeys.clear()
        submittedNamespaces.clear()
        submittedBytes = 0
        namespaces = listOf("public-services")
        organizationSlug = "acme"
        rejectCarriedIdentity = false
        discoveryResponse = { Response(200, """{"version":1,"issuer":"$baseUrl","baseUrl":"$baseUrl"}""") }
        oauthMetadataIssuer = null
        oauthEndpointBaseUrl = null
        tokenResponse = { Response(200, defaultToken()) }
        submitResponse = { Response(200, publicationBody(remotePublicationId, "QUEUED")) }
        statusResponse = { Response(200, publicationBody(remotePublicationId, "ACCEPTED")) }
        hostedCatalogs.clear()
        catalogLookups.clear()
        archiveDownloads.clear()
        searchQueries.clear()
        catalogResponse = null
        archiveResponse = null
        nextSearchCursor = null
    }

    private var stopped = false

    /** Idempotent, so a test can take Exchange down mid-run and still close in an `@AfterAll`. */
    override fun close() {
        if (!stopped) {
            stopped = true
            server.stop(0)
        }
    }

    data class Response(val status: Int, val body: String)

    private fun HttpExchange.form(): Map<String, String> = requestBody.bufferedReader().use { it.readText() }
        .split('&').filter(String::isNotBlank).associate { field ->
            val (name, value) = field.split('=', limit = 2)
            URLDecoder.decode(name, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
        }

    private fun HttpExchange.queryParam(name: String): String? = requestURI.query
        ?.split('&')
        ?.filter(String::isNotBlank)
        ?.map { it.split('=', limit = 2) }
        ?.firstOrNull { it[0] == name }
        ?.getOrNull(1)
        ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }

    /** Bytes rather than JSON — a release archive is the one response that is not a document. */
    private fun HttpExchange.respondBytes(status: Int, contentType: String, bytes: ByteArray, headers: Map<String, String> = emptyMap()) {
        runCatching { requestBody.readBytes() }
        responseHeaders.add("Content-Type", contentType)
        headers.forEach { (name, value) -> responseHeaders.add(name, value) }
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
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
        val OWNER_ORGANIZATION_ID: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000b1")
        const val PROBLEM_NOT_FOUND = """{"type":"about:blank","title":"Not Found","status":404}"""
        val OAUTH_APPLICATION_ID: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000a1")

        /** Fixed so publication fixtures never carry a moving value. */
        private const val FIXED_TIMESTAMP = "2026-01-01T00:00:00Z"

        private const val CONNECTION_ID_HIGH = 0x4000_8000_0000_0000L

        /** Matches the `namespace` form part past whatever headers the client added to it. */
        private val NAMESPACE_PART =
            Regex("""name="namespace"(?:\r?\n[^\r\n]+)*\r?\n\r?\n([^\r\n]*)""")
    }
}

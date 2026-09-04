// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.exchange.client.api.CatalogsApi
import app.epistola.exchange.client.api.ConnectionsApi
import app.epistola.exchange.client.api.PublicationsApi
import app.epistola.exchange.client.model.CatalogRelease
import app.epistola.exchange.client.model.CatalogSummary
import app.epistola.exchange.client.model.PublicationSubmission
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.JsonNode
import java.io.File
import java.net.URI
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

/**
 * A release archive is larger than this installation will import.
 *
 * Its own type because the remedy is an operator's (`epistola.catalog.max-zip-size`), not a retry.
 * [declaredBytes] is null when the server never said and the cap was hit while reading.
 */
class ExchangeArchiveTooLargeException(val declaredBytes: Long?, val maxBytes: Long) :
    RuntimeException(
        "Release archive is larger than the ${maxBytes / 1024 / 1024} MB this installation imports" +
            (declaredBytes?.let { " (Exchange reported ${it / 1024 / 1024} MB)" } ?: "") +
            ". Raise epistola.catalog.max-zip-size to install it.",
    )

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
    @Qualifier("exchangeArchiveRestClient") private val archiveHttp: RestClient,
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
            authorizationRequestEndpoint =
            requireIssuerOrigin(metadata.requiredText("authorization_request_endpoint"), issuer, "authorization endpoint"),
            tokenEndpoint = requireIssuerOrigin(metadata.requiredText("token_endpoint"), issuer, "token endpoint"),
        )
    }

    /**
     * Keeps a discovered endpoint on the issuer's own origin.
     *
     * The issuer is the thing an operator chose to trust; the endpoints are whatever the document
     * at that address says. Without this, a poisoned or compromised discovery response names any
     * host it likes as the token endpoint — and the token endpoint is where the client secret and
     * the refresh token are sent, so the redirect would be the whole credential. The OAuth metadata
     * spec permits a different origin; nothing here needs that, and needing it later is a better
     * problem than trusting it now.
     */
    private fun requireIssuerOrigin(url: String, issuer: String, what: String): String {
        val endpoint = requireSecure(url, what)
        if (origin(endpoint, what) != origin(issuer, "issuer")) {
            throw ExchangeProtocolException(
                "Exchange $what '$endpoint' is not on the issuer's origin ('$issuer'); refusing to send credentials elsewhere",
            )
        }
        return endpoint
    }

    /** Scheme, host and port, with the scheme's default port made explicit so `:443` compares equal. */
    private fun origin(url: String, what: String): String {
        val uri = runCatching { URI(url) }.getOrElse { throw ExchangeProtocolException("Exchange $what is not a valid URL: '$url'") }
        val host = uri.host ?: throw ExchangeProtocolException("Exchange $what has no host: '$url'")
        val port = if (uri.port != -1) {
            uri.port
        } else if (uri.scheme == "https") {
            443
        } else {
            80
        }
        return "${uri.scheme}://${host.lowercase()}:$port"
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
        val context = ConnectionsApi(authorized(endpoints.baseUrl, accessToken)).getTenantConnectionContext()
        return ExchangeConnectionContext(
            context.tenantConnectionId,
            context.tenantConnectionReference,
            context.organization.slug,
            context.organization.name,
            context.scopes.mapTo(mutableSetOf()) { it.value },
            context.namespaces.mapTo(mutableSetOf()) { it.slug },
        )
    }

    fun disconnect(baseUrl: String, accessToken: String) {
        ConnectionsApi(authorized(baseUrl, accessToken)).disconnectTenantConnection()
    }

    /**
     * The generated client takes the archive as a [File], and Suite holds it in memory: a catalog
     * is not spilled to disk to satisfy a signature. Only the body encoding is ours - the method,
     * path and headers are read off the generated request config, so a contract change moves them
     * here too rather than leaving a hand-copied path to rot.
     */
    fun submit(
        baseUrl: String,
        accessToken: String,
        namespace: String,
        archive: ByteArray,
        idempotencyKey: UUID,
    ): ExchangePublicationResponse {
        val client = authorized(baseUrl, accessToken)
        val request = PublicationsApi(client)
            .submitPublicationRequestConfig(idempotencyKey.toString(), namespace, ARCHIVE_PART_PLACEHOLDER)
        val multipart = MultipartBodyBuilder().apply {
            part("namespace", namespace)
            part(
                "catalog",
                object : ByteArrayResource(archive) {
                    override fun getFilename(): String = "catalog.zip"
                },
            ).contentType(MediaType.parseMediaType("application/zip"))
        }.build()
        val submission = client.post().uri(request.path)
            .headers { headers ->
                request.headers
                    .filterKeys { !it.equals(HttpHeaders.CONTENT_TYPE, ignoreCase = true) }
                    .forEach { (name, value) -> headers.set(name, value) }
            }.contentType(MediaType.MULTIPART_FORM_DATA).body(multipart)
            .retrieve().body(PublicationSubmission::class.java)
            ?: throw ExchangeProtocolException("Exchange publication returned an empty response")
        return submission.toResponse()
    }

    fun publication(baseUrl: String, accessToken: String, id: UUID): ExchangePublicationResponse = PublicationsApi(authorized(baseUrl, accessToken)).getPublicationSubmission(id).toResponse()

    /**
     * Catalogs on Exchange matching [query], newest-first by however Exchange orders them.
     *
     * `READ` is enough for this and for everything else an install needs; nothing here asks for the
     * `USAGE` or `UPGRADES` scopes, which would mean reauthorizing every existing connection.
     */
    fun searchCatalogs(
        baseUrl: String,
        accessToken: String,
        query: String?,
        cursor: String?,
        limit: Int,
    ): ExchangeCatalogPage {
        val page = CatalogsApi(authorized(baseUrl, accessToken))
            .searchCatalogs(query?.takeIf(String::isNotBlank), cursor, limit)
        return ExchangeCatalogPage(page.items.map { it.toSummary() }, cursorOf(page.links.next))
    }

    fun catalog(baseUrl: String, accessToken: String, namespace: String, catalogKey: String): ExchangeCatalogSummary = CatalogsApi(authorized(baseUrl, accessToken)).getCatalog(namespace, catalogKey).toSummary()

    fun releases(
        baseUrl: String,
        accessToken: String,
        namespace: String,
        catalogKey: String,
        cursor: String? = null,
        limit: Int = RELEASE_PAGE_SIZE,
    ): List<ExchangeCatalogRelease> = CatalogsApi(authorized(baseUrl, accessToken))
        .listCatalogReleases(namespace, catalogKey, cursor, limit)
        .items
        .map { it.toRelease() }

    /**
     * The release archive, as bytes.
     *
     * The generated method returns a [File] through a JSON converter and cannot carry a zip, so
     * only the body handling is ours — method and path come off the generated request config, as
     * they do for [submit], so a contract change moves them rather than leaving a copied path to
     * rot.
     *
     * [maxBytes] is enforced here rather than left to the importer. `Content-Length` is checked
     * first because it is free, but it is the server's claim about its own body, so the read is
     * capped as well: one byte over the limit is enough to know, and buffering a hostile or
     * misconfigured response to find out is exactly what the limit exists to prevent.
     */
    fun downloadArchive(
        baseUrl: String,
        accessToken: String,
        namespace: String,
        catalogKey: String,
        version: String,
        maxBytes: Long,
    ): ByteArray {
        val request = CatalogsApi(archiveHttp).downloadCatalogReleaseRequestConfig(namespace, catalogKey, version)
        // `path` is still a template and the values sit beside it in `params`, so both come from the
        // generated config rather than being interpolated here.
        return archiveHttp.get()
            .uri("${baseUrl.trimEnd('/')}${request.path}", request.params)
            .headers { headers -> request.headers.forEach { (name, value) -> headers.set(name, value) } }
            .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
            .exchange { _, response ->
                // `exchange` turns off the default status handling, so refusals are re-raised as the
                // same exceptions every other call produces - that is what ExchangeFailure reads to
                // tell "reconnect" apart from "this catalog is gone".
                if (response.statusCode.isError) {
                    val detail = runCatching { response.body.readNBytes(ERROR_BODY_SNIPPET) }.getOrDefault(ByteArray(0))
                    throw if (response.statusCode.is4xxClientError) {
                        HttpClientErrorException.create(response.statusCode, response.statusText, response.headers, detail, null)
                    } else {
                        HttpServerErrorException.create(response.statusCode, response.statusText, response.headers, detail, null)
                    }
                }
                val declared = response.headers.contentLength
                if (declared > maxBytes) throw ExchangeArchiveTooLargeException(declared, maxBytes)
                val bytes = response.body.readNBytes((maxBytes + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                if (bytes.size > maxBytes) throw ExchangeArchiveTooLargeException(null, maxBytes)
                bytes
            }
            ?: throw ExchangeProtocolException("Exchange returned an empty archive for $namespace/$catalogKey $version")
    }

    private fun CatalogSummary.toSummary() = ExchangeCatalogSummary(
        namespace = namespace,
        catalogKey = key,
        name = name,
        description = description,
        organizationName = ownerOrganizationName,
        visibility = visibility.value,
        latestVersion = latestVersion,
    )

    private fun CatalogRelease.toRelease() = ExchangeCatalogRelease(
        namespace = namespace,
        catalogKey = catalogKey,
        version = version,
        fingerprint = fingerprint,
        sha256 = sha256,
        sizeBytes = propertySize,
        scanState = scanState.value,
        availability = availability.value,
        publishedAt = publishedAt,
    )

    /**
     * Exchange returns the next page as a URL, not a cursor. Only the cursor is kept: following an
     * absolute URL would let a response choose the host the next request goes to, which is the
     * discipline [requireIssuerOrigin] exists to hold everywhere else.
     */
    private fun cursorOf(next: String?): String? = next
        ?.let { runCatching { UriComponentsBuilder.fromUriString(it).build().queryParams.getFirst("cursor") }.getOrNull() }
        ?.takeIf(String::isNotBlank)

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

    /**
     * A [RestClient] aimed at one Exchange and carrying one tenant's bearer token, built by mutating
     * the configured one so connect and read timeouts are inherited rather than re-declared. The
     * generated APIs take the client as a constructor argument, so they are per-call objects: the
     * token is per tenant and rotates, and nothing about them is worth caching.
     */
    private fun authorized(baseUrl: String, accessToken: String): RestClient = http.mutate()
        .baseUrl(baseUrl.trimEnd('/'))
        .defaultHeaders { it.setBearerAuth(accessToken) }
        .build()

    /**
     * [PublicationSubmission.State] is a closed enum, so a newer Exchange reporting a state this
     * Suite has never heard of fails to deserialize. That surfaces as a transient publication
     * failure and a retry, which is the same treatment as any other protocol error and is why
     * `state` stays a [String] on the way out: [CatalogPublicationStatus.fromRemote] treats
     * anything it does not recognise as still-undecided rather than as a verdict.
     */
    private fun PublicationSubmission.toResponse() = ExchangePublicationResponse(id, state.value, errorCode, errorDetail)

    private companion object {
        /** Enough releases for a version picker without paging; the newest are what anyone installs. */
        const val RELEASE_PAGE_SIZE = 50

        /** How much of a refusal body is kept for the recorded reason. Enough for a ProblemDetail. */
        const val ERROR_BODY_SNIPPET = 4096

        /**
         * Never opened. The generated multipart signature demands a [File]; the bytes are supplied
         * separately above, and only the request's method, path and headers are taken from it.
         */
        val ARCHIVE_PART_PLACEHOLDER = File("catalog.zip")
    }

    private fun JsonNode.requiredText(name: String): String = path(name).stringValue()?.takeIf(String::isNotBlank)
        ?: throw ExchangeProtocolException("Exchange response is missing '$name'")
}

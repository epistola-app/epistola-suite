// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.registry

import app.epistola.suite.catalog.sources.CatalogSourceUnavailableException
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

/**
 * A client for one registry's OCI distribution API (`/v2/`), for one piece of work: it remembers
 * the authorization a challenge earned, so create one per operation ([CatalogRegistries.client]).
 *
 * - **Authentication**: a `401` is answered once per request. A `Basic` challenge gets the
 *   configured credentials; a `Bearer` one is taken to its token service (`realm`, `service`,
 *   `scope`), with those credentials when there are any, and the `token` or `access_token` it
 *   returns is used from then on.
 * - **Redirects** are followed by hand, at most [maxRedirects], because a registry commonly sends
 *   blob downloads to object storage on another host, and the registry's credentials must not go
 *   with them. A redirect to plaintext `http://` is followed only when [allowHttp] is.
 * - **Sizes** are capped while reading: [MAX_MANIFEST_BYTES] for manifests and listings, the
 *   caller's limit for a blob, which is also verified against its digest.
 */
class OciRegistryClient(
    private val registry: CatalogRegistryProperties.Registry,
    private val http: HttpClient,
    private val mapper: ObjectMapper,
    private val allowHttp: Boolean,
    private val maxRedirects: Int,
    private val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
) {
    private val base: URI = registry.baseUri
    private var authorization: String? = null

    /** A manifest and its digest, as served. */
    data class PulledManifest(val manifest: OciManifest, val digest: String, val bytes: ByteArray)

    /** The manifest [reference] (a tag or digest) names, or null when the registry has none. */
    fun manifest(repository: String, reference: String): PulledManifest? {
        val response = send("GET", api("/v2/$repository/manifests/$reference"), repository, PULL, headers = mapOf("Accept" to OciMediaTypes.MANIFEST))
        return response.use {
            when (it.statusCode()) {
                200 -> {
                    val bytes = it.readCapped(MAX_MANIFEST_BYTES, "manifest")
                    val manifest = try {
                        OciManifest.parse(mapper, bytes)
                    } catch (e: IllegalArgumentException) {
                        throw CatalogSourceUnavailableException("${registry.name} answered with something that is not a manifest for $repository:$reference: ${e.message}", badGateway = true, cause = e)
                    }
                    PulledManifest(manifest, OciDigest.sha256(bytes), bytes)
                }

                404 -> null

                else -> throw it.failure("reading $repository:$reference")
            }
        }
    }

    /** Every tag of [repository], following the registry's pagination; empty when there is no such repository. */
    fun tags(repository: String): List<String> {
        val tags = linkedSetOf<String>()
        var next: URI? = api("/v2/$repository/tags/list?n=$TAGS_PAGE")
        var pages = 0
        while (next != null && pages++ < MAX_TAG_PAGES) {
            val page = next
            next = send("GET", page, repository, PULL).use { response ->
                when (response.statusCode()) {
                    200 -> {
                        val found = try {
                            parseTagList(mapper, response.readCapped(MAX_MANIFEST_BYTES, "tag list"))
                        } catch (e: tools.jackson.core.JacksonException) {
                            throw CatalogSourceUnavailableException("${registry.name} answered with something that is not a tag list for $repository", badGateway = true, cause = e)
                        }
                        val before = tags.size
                        tags += found
                        // Only a page that added something can have a next one; a registry that
                        // ignores pagination would otherwise be asked for the same page for ever.
                        if (tags.size == before) null else LinkHeader.next(response.headers().firstValue("Link").orElse(null), page)
                    }

                    404 -> null

                    else -> throw response.failure("listing the versions of $repository")
                }
            }
        }
        return tags.toList()
    }

    fun blobExists(repository: String, digest: String): Boolean = send("HEAD", api("/v2/$repository/blobs/$digest"), repository, PULL).use {
        when (it.statusCode()) {
            200 -> true
            404 -> false
            else -> throw it.failure("checking for blob $digest in $repository")
        }
    }

    /** Uploads [bytes] unless the repository has them already; their descriptor. */
    fun pushBlob(repository: String, mediaType: String, bytes: ByteArray, annotations: Map<String, String> = emptyMap()): OciDescriptor {
        val digest = OciDigest.sha256(bytes)
        val descriptor = OciDescriptor(mediaType, digest, bytes.size.toLong(), annotations)
        if (blobExists(repository, digest)) return descriptor
        val started = send("POST", api("/v2/$repository/blobs/uploads/"), repository, PUSH, body = ByteArray(0))
        val location = started.use {
            if (it.statusCode() != 202) throw it.failure("starting an upload to $repository")
            it.headers().firstValue("Location").orElseThrow { CatalogSourceUnavailableException("${registry.name} started an upload without saying where to send it", badGateway = true) }
        }
        val upload = started.uri().resolve(location)
        val separator = if (upload.rawQuery == null) "?" else "&"
        val target = URI(upload.toString() + separator + "digest=" + URLEncoder.encode(digest, StandardCharsets.UTF_8))
        send("PUT", target, repository, PUSH, body = bytes, headers = mapOf("Content-Type" to "application/octet-stream")).use {
            if (it.statusCode() != 201) throw it.failure("uploading blob $digest to $repository")
        }
        return descriptor
    }

    /** Stores [manifest] under [tag]; the digest the registry names it by. */
    fun pushManifest(repository: String, tag: String, manifest: ByteArray): String = send(
        "PUT",
        api("/v2/$repository/manifests/$tag"),
        repository,
        PUSH,
        body = manifest,
        headers = mapOf("Content-Type" to OciMediaTypes.MANIFEST),
    ).use {
        if (it.statusCode() != 201) throw it.failure("storing $repository:$tag")
        it.headers().firstValue("Docker-Content-Digest").orElse(null) ?: OciDigest.sha256(manifest)
    }

    /**
     * The blob [descriptor] names, at most [maxBytes], verified: its size and its SHA-256 must be
     * what the descriptor says, or nothing is returned.
     */
    fun pullBlob(repository: String, descriptor: OciDescriptor, maxBytes: Long): ByteArray {
        if (descriptor.size > maxBytes) throw tooLarge(descriptor.size, maxBytes)
        return send("GET", api("/v2/$repository/blobs/${descriptor.digest}"), repository, PULL).use {
            if (it.statusCode() != 200) throw it.failure("downloading ${descriptor.digest} from $repository")
            val bytes = it.body().use { stream -> readAtMost(stream, maxBytes) } ?: throw tooLarge(null, maxBytes)
            val actual = OciDigest.sha256(bytes)
            if (actual != descriptor.digest || bytes.size.toLong() != descriptor.size) {
                throw CatalogSourceUnavailableException(
                    "${registry.name} sent ${bytes.size} bytes with digest $actual for ${descriptor.digest} (${descriptor.size} bytes) in $repository; nothing was installed",
                    badGateway = true,
                )
            }
            bytes
        }
    }

    private fun tooLarge(size: Long?, max: Long) = ValidationException(
        "version",
        "The release is ${size?.let { "${it / 1024 / 1024} MB" } ?: "larger than that"}, more than the ${max / 1024 / 1024} MB this installation installs. Raise epistola.catalog.max-zip-size to install it.",
        ValidationCode.REGISTRY_ARCHIVE_REJECTED,
    )

    // --- HTTP ---------------------------------------------------------------------------------

    private fun api(pathAndQuery: String): URI = URI(base.toString() + pathAndQuery)

    /** A response to close after use; its body is a stream, so nothing is read that is not wanted. */
    private class Response(private val inner: HttpResponse<InputStream>) : AutoCloseable {
        fun statusCode(): Int = inner.statusCode()
        fun headers() = inner.headers()
        fun uri(): URI = inner.uri()
        fun body(): InputStream = inner.body()
        override fun close() = inner.body().close()
    }

    private fun send(
        method: String,
        uri: URI,
        repository: String,
        actions: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        var target = uri
        var currentMethod = method
        var currentBody = body
        var redirects = 0
        var challenged = false
        while (true) {
            val sameOrigin = sameOrigin(target)
            val request = HttpRequest.newBuilder(target).timeout(requestTimeout).apply {
                headers.forEach { (name, value) -> header(name, value) }
                if (sameOrigin) authorization?.let { header("Authorization", it) }
                method(currentMethod, currentBody?.let { HttpRequest.BodyPublishers.ofByteArray(it) } ?: HttpRequest.BodyPublishers.noBody())
            }.build()
            val response = Response(transport { http.send(request, HttpResponse.BodyHandlers.ofInputStream()) })
            val status = response.statusCode()
            if (status == 401 && sameOrigin && !challenged) {
                challenged = true
                val challenge = AuthChallenge.parse(response.headers().firstValue("WWW-Authenticate").orElse(null))
                response.close()
                authorization = challenge?.let { authenticate(it, repository, actions) }
                    ?: throw CatalogSourceUnavailableException(
                        "${registry.name} refused access to $repository" +
                            if (registry.username.isNullOrEmpty()) " and no credentials are configured for it" else " with the configured credentials",
                        badGateway = true,
                    )
                continue
            }
            if (status in REDIRECTS) {
                val location = response.headers().firstValue("Location").orElse(null)
                response.close()
                if (location == null) throw CatalogSourceUnavailableException("${registry.name} redirected without saying where", badGateway = true)
                if (++redirects > maxRedirects) throw CatalogSourceUnavailableException("${registry.name} redirected more than $maxRedirects times", badGateway = true)
                target = target.resolve(location)
                requireAllowedScheme(target)
                if (status == 303) {
                    currentMethod = "GET"
                    currentBody = null
                }
                continue
            }
            return response
        }
    }

    /** The authorization a challenge asks for, or null when it cannot be met. */
    private fun authenticate(challenge: AuthChallenge, repository: String, actions: String): String? {
        val basic = registry.username?.takeIf { it.isNotEmpty() }?.let { user ->
            "Basic " + Base64.getEncoder().encodeToString("$user:${registry.password.orEmpty()}".toByteArray(StandardCharsets.UTF_8))
        }
        return when {
            challenge.isBasic -> basic
            challenge.isBearer -> bearerToken(challenge, repository, actions, basic)?.let { "Bearer $it" }
            else -> null
        }
    }

    /**
     * Asks the token service a Bearer challenge names. Not exercised against a real token service
     * by the suite's tests (see `docs/catalog-registry.md`).
     */
    private fun bearerToken(challenge: AuthChallenge, repository: String, actions: String, basic: String?): String? {
        val realm = challenge.params["realm"]?.let { runCatching { URI(it) }.getOrNull() } ?: return null
        requireAllowedScheme(realm)
        val query = buildList {
            challenge.params["service"]?.let { add("service=" + URLEncoder.encode(it, StandardCharsets.UTF_8)) }
            add("scope=" + URLEncoder.encode(challenge.params["scope"] ?: "repository:$repository:$actions", StandardCharsets.UTF_8))
        }.joinToString("&")
        val uri = URI(realm.toString() + (if (realm.rawQuery == null) "?" else "&") + query)
        val request = HttpRequest.newBuilder(uri).timeout(requestTimeout).GET().apply { basic?.let { header("Authorization", it) } }.build()
        val response = Response(transport { http.send(request, HttpResponse.BodyHandlers.ofInputStream()) })
        return response.use {
            if (it.statusCode() != 200) return null
            val root = runCatching { mapper.readTree(it.readCapped(MAX_MANIFEST_BYTES, "token")) }.getOrNull() ?: return null
            listOf("token", "access_token").firstNotNullOfOrNull { name -> root.path(name).takeIf { node -> node.isString }?.stringValue()?.takeIf(String::isNotBlank) }
        }
    }

    private fun sameOrigin(uri: URI): Boolean = uri.scheme.equals(base.scheme, ignoreCase = true) &&
        uri.host.equals(base.host, ignoreCase = true) &&
        effectivePort(uri) == effectivePort(base)

    private fun effectivePort(uri: URI): Int = if (uri.port != -1) {
        uri.port
    } else if (uri.scheme.equals("https", ignoreCase = true)) {
        443
    } else {
        80
    }

    private fun requireAllowedScheme(uri: URI) {
        val scheme = uri.scheme?.lowercase()
        if (scheme == "https" || (scheme == "http" && allowHttp)) return
        throw CatalogSourceUnavailableException("${registry.name} pointed to $uri, which this installation does not contact (plaintext http is off)", badGateway = true)
    }

    private fun <T> transport(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw CatalogSourceUnavailableException("The registry ${registry.name} (${registry.location}) could not be reached: ${e.message ?: e.javaClass.simpleName}", cause = e)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw CatalogSourceUnavailableException("Interrupted while contacting ${registry.name}", cause = e)
    }

    private fun Response.readCapped(max: Long, what: String): ByteArray = body().use { readAtMost(it, max) }
        ?: throw CatalogSourceUnavailableException("${registry.name} sent a $what larger than ${max / 1024} KB", badGateway = true)

    private fun Response.failure(doing: String): CatalogSourceUnavailableException {
        val status = statusCode()
        val detail = runCatching { body().use { readAtMost(it, ERROR_DETAIL_BYTES)?.toString(StandardCharsets.UTF_8) } }.getOrNull()?.trim()?.take(300)
        val what = when (status) {
            401, 403 -> "refused it ($status)"
            else -> "answered $status"
        }
        return CatalogSourceUnavailableException(
            "${registry.name} $what while $doing" + (detail?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""),
            badGateway = status != 503,
        )
    }

    companion object {
        const val PULL = "pull"
        const val PUSH = "pull,push"

        /** The distribution spec has registries accept manifests of at least 4 MiB; nothing of ours is near. */
        const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024
        private const val ERROR_DETAIL_BYTES = 4096L
        private const val TAGS_PAGE = 1000
        private const val MAX_TAG_PAGES = 100
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        val DEFAULT_REQUEST_TIMEOUT: Duration = Duration.ofMinutes(2)

        /** At most [max] bytes of [stream], or null when it holds more. */
        internal fun readAtMost(stream: InputStream, max: Long): ByteArray? {
            val bytes = stream.readNBytes((max + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            return if (bytes.size > max) null else bytes
        }
    }
}

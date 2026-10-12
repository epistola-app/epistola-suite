// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.outbound

import org.apache.hc.client5.http.DnsResolver
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.client5.http.impl.routing.SystemDefaultRoutePlanner
import org.apache.hc.core5.util.Timeout
import org.springframework.beans.factory.DisposableBean
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.time.Duration

/**
 * The HTTP client behind catalog and code-list fetches.
 *
 * Built so that [OutboundUrlGuard] decides every hop, not just the first:
 *
 * - **Addresses are checked where they are dialled.** The connection manager resolves host names
 *   through [OutboundUrlGuard.resolveForConnection], which refuses a non-public address and returns
 *   only the addresses it checked. A host that answers the pre-flight check with a public address
 *   and the connection with a private one (DNS rebinding) is refused at connect. TLS still verifies
 *   and sends SNI for the host name, because the client connects by name to a pinned address.
 * - **Redirects are followed by hand**, at most `max-redirects` of them, each re-checked as a fresh
 *   request. Credentials are dropped when a redirect leaves the original origin.
 * - **Responses are capped.** A body larger than the caller's limit is refused while it is being
 *   read, so an endless response cannot exhaust memory.
 *
 * Status failures surface as Spring's [HttpClientErrorException] / [HttpServerErrorException] and
 * transport failures as `ResourceAccessException`, as they did with the previous client, so the
 * upstream probe's mapping is unchanged. Response bodies are never copied into those exceptions:
 * their messages reach the UI, and a body is exactly what a forged request would want to read back.
 */
class OutboundHttpFetcher(
    private val guard: OutboundUrlGuard,
    connectTimeout: Duration = Duration.ofSeconds(5),
    readTimeout: Duration = Duration.ofSeconds(15),
) : DisposableBean {

    private val httpClient: CloseableHttpClient = HttpClients.custom()
        .setConnectionManager(
            PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(GuardedDnsResolver(guard))
                .setDefaultConnectionConfig(
                    ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(connectTimeout))
                        .setSocketTimeout(Timeout.of(readTimeout))
                        .build(),
                )
                .build(),
        )
        .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(Timeout.of(readTimeout)).build())
        // Honour the JVM's standard proxy settings, as the HttpURLConnection-based client did.
        .setRoutePlanner(SystemDefaultRoutePlanner(ProxySelector.getDefault()))
        .disableRedirectHandling()
        .disableAutomaticRetries()
        .disableCookieManagement()
        .build()

    private val restClient: RestClient = RestClient.builder()
        .requestFactory(HttpComponentsClientHttpRequestFactory(httpClient))
        .defaultHeader(HttpHeaders.ACCEPT, "application/json")
        .build()

    /**
     * GETs [url] and returns at most [maxBytes] bytes of body. The caller has already passed [url]
     * through the guard; every redirect is checked here.
     */
    fun get(url: String, headers: Map<String, String>, allowHttp: Boolean, maxBytes: Long): ByteArray {
        val origin = URI(url)
        var current = origin
        var requestHeaders = headers
        var redirects = 0
        while (true) {
            when (val hop = fetchOnce(current, requestHeaders, maxBytes)) {
                is Hop.Body -> return hop.bytes

                is Hop.Redirect -> {
                    if (redirects >= guard.maxRedirects) {
                        throw OutboundFetchException("Too many redirects (more than ${guard.maxRedirects})")
                    }
                    val next = try {
                        current.resolve(hop.location)
                    } catch (_: IllegalArgumentException) {
                        throw OutboundUrlRejectedException("The source redirected to an invalid URL")
                    }
                    guard.checkRedirect(current, next, allowHttp)
                    if (!sameOrigin(origin, next)) requestHeaders = requestHeaders.filterKeys { it.lowercase() !in CREDENTIAL_HEADERS }
                    current = next
                    redirects++
                }
            }
        }
    }

    private sealed interface Hop {
        class Body(val bytes: ByteArray) : Hop
        class Redirect(val location: String) : Hop
    }

    private fun fetchOnce(uri: URI, headers: Map<String, String>, maxBytes: Long): Hop = restClient.get()
        .uri(uri)
        .headers { h -> headers.forEach { (name, value) -> h.set(name, value) } }
        .exchange { _, response ->
            val status = response.statusCode
            when {
                status.is2xxSuccessful -> {
                    val declared = response.headers.contentLength
                    if (declared > maxBytes) throw tooLarge(maxBytes)
                    Hop.Body(readCapped(response.body, maxBytes))
                }

                status.is3xxRedirection && status.value() in REDIRECT_STATUSES -> {
                    val location = response.headers.getFirst(HttpHeaders.LOCATION)
                        ?: throw OutboundFetchException("Redirect without a Location header")
                    Hop.Redirect(location)
                }

                else -> throw statusException(status, response.statusText, response.headers)
            }
        }

    private fun statusException(status: HttpStatusCode, statusText: String, headers: HttpHeaders): Exception = when {
        status.is4xxClientError -> HttpClientErrorException.create(status, statusText, headers, ByteArray(0), null)
        status.is5xxServerError -> HttpServerErrorException.create(status, statusText, headers, ByteArray(0), null)
        else -> OutboundFetchException("Unexpected response status ${status.value()}")
    }

    private fun readCapped(body: InputStream, maxBytes: Long): ByteArray {
        val bytes = body.readNBytes((maxBytes + 1).coerceAtMost(Int.MAX_VALUE.toLong() - 8).toInt())
        if (bytes.size > maxBytes) throw tooLarge(maxBytes)
        return bytes
    }

    private fun tooLarge(maxBytes: Long) = OutboundFetchException("The response is larger than the $maxBytes-byte limit")

    private fun sameOrigin(a: URI, b: URI): Boolean = a.scheme.equals(b.scheme, ignoreCase = true) &&
        a.host.equals(b.host, ignoreCase = true) &&
        effectivePort(a) == effectivePort(b)

    private fun effectivePort(uri: URI): Int = if (uri.port != -1) {
        uri.port
    } else if (uri.scheme.equals("https", ignoreCase = true)) {
        443
    } else {
        80
    }

    override fun destroy() {
        httpClient.close()
    }

    /** Routes every host-name resolution of the connection manager through the guard. */
    private class GuardedDnsResolver(private val guard: OutboundUrlGuard) : DnsResolver {
        override fun resolve(host: String): Array<InetAddress> = guard.resolveForConnection(host)

        override fun resolve(host: String, port: Int): List<InetSocketAddress> = guard.resolveForConnection(host).map { InetSocketAddress(it, port) }

        override fun resolveCanonicalHostname(host: String): String = host
    }

    private companion object {
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        val CREDENTIAL_HEADERS = setOf("authorization", "x-api-key", "cookie")
    }
}

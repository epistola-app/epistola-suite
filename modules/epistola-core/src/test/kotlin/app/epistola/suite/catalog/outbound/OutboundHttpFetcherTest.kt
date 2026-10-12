// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.outbound

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.web.client.HttpClientErrorException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The fetcher against a real loopback server: redirects, credentials across origins, response
 * caps, and the connection-time address check that defeats DNS rebinding.
 */
class OutboundHttpFetcherTest {

    private val servers = mutableListOf<HttpServer>()

    /** Every request a server received: path plus the Authorization header it carried. */
    private val requests = CopyOnWriteArrayList<Pair<String, String?>>()

    @AfterEach
    fun stop() = servers.forEach { it.stop(0) }

    private fun server(handler: (HttpExchange) -> Unit): Int {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.path to exchange.requestHeaders.getFirst("Authorization")
            exchange.use(handler)
        }
        server.start()
        servers += server
        return server.address.port
    }

    private fun HttpExchange.respond(status: Int, body: ByteArray = ByteArray(0), headers: Map<String, String> = emptyMap()) {
        headers.forEach { (k, v) -> responseHeaders.add(k, v) }
        sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) responseBody.write(body)
    }

    private fun reader(
        allowPrivate: Boolean = true,
        resolver: HostResolver = HostResolver.SYSTEM,
        maxBytes: Long = 1024 * 1024,
    ): OutboundSourceReader {
        val guard = OutboundUrlGuard(
            CatalogSourceProperties(allowHttp = true, outbound = CatalogSourceProperties.Outbound(allowPrivateNetworks = allowPrivate)),
            resolver,
        )
        return OutboundSourceReader(guard, OutboundHttpFetcher(guard), DefaultResourceLoader(), maxBytes)
    }

    @Test
    fun `a loopback source is refused when private networks are not allowed`() {
        val port = server { it.respond(200, "{}".toByteArray()) }

        assertThatThrownBy { reader(allowPrivate = false).readSource("http://127.0.0.1:$port/catalog.json", emptyMap(), allowHttp = true) }
            .isInstanceOf(OutboundUrlRejectedException::class.java)
        assertThat(requests).isEmpty()
    }

    @Test
    fun `a host that resolves differently at connect time is refused at connect`() {
        val port = server { it.respond(200, "{}".toByteArray()) }
        val lookups = AtomicInteger()
        // First answer (the pre-flight check) is public, every later one (the connection) is loopback.
        val rebinding = HostResolver { host ->
            if (host != "rebind.example") throw UnknownHostException(host)
            val answer = if (lookups.getAndIncrement() == 0) "93.184.216.34" else "127.0.0.1"
            arrayOf(InetAddress.ofLiteral(answer))
        }

        assertThatThrownBy { reader(allowPrivate = false, resolver = rebinding).readSource("http://rebind.example:$port/catalog.json", emptyMap(), allowHttp = true) }
            .isInstanceOf(OutboundUrlRejectedException::class.java)
        assertThat(lookups.get()).isGreaterThanOrEqualTo(2)
        assertThat(requests).isEmpty()
    }

    @Test
    fun `a redirect to a link-local address is not followed`() {
        val port = server { it.respond(302, headers = mapOf("Location" to "http://169.254.169.254/latest/meta-data/")) }

        assertThatThrownBy { reader().readSource("http://127.0.0.1:$port/catalog.json", emptyMap(), allowHttp = true) }
            .isInstanceOf(OutboundUrlRejectedException::class.java)
        assertThat(requests.map { it.first }).containsExactly("/catalog.json")
    }

    @Test
    fun `a redirect to a file URL is not followed`() {
        val port = server { it.respond(301, headers = mapOf("Location" to "file:///etc/passwd")) }

        assertThatThrownBy { reader().readSource("http://127.0.0.1:$port/catalog.json", emptyMap(), allowHttp = true) }
            .isInstanceOf(OutboundUrlRejectedException::class.java)
    }

    @Test
    fun `at most three redirects are followed`() {
        val port = server { exchange ->
            val n = exchange.requestURI.path.removePrefix("/r").removeSuffix(".json").toInt()
            exchange.respond(302, headers = mapOf("Location" to "/r${n + 1}.json"))
        }

        assertThatThrownBy { reader().readSource("http://127.0.0.1:$port/r0.json", emptyMap(), allowHttp = true) }
            .isInstanceOf(OutboundFetchException::class.java)
            .hasMessageContaining("Too many redirects")
        assertThat(requests.map { it.first }).containsExactly("/r0.json", "/r1.json", "/r2.json", "/r3.json")
    }

    @Test
    fun `credentials follow a same-origin redirect and are dropped across origins`() {
        val other = server { it.respond(200, "{}".toByteArray()) }
        val origin = server { exchange ->
            when (exchange.requestURI.path) {
                "/catalog.json" -> exchange.respond(302, headers = mapOf("Location" to "/moved.json"))
                else -> exchange.respond(302, headers = mapOf("Location" to "http://127.0.0.1:$other/elsewhere.json"))
            }
        }

        val bytes = reader().readSource("http://127.0.0.1:$origin/catalog.json", mapOf("Authorization" to "Bearer secret"), allowHttp = true)

        assertThat(String(bytes)).isEqualTo("{}")
        assertThat(requests).containsExactly(
            "/catalog.json" to "Bearer secret",
            "/moved.json" to "Bearer secret",
            "/elsewhere.json" to null,
        )
    }

    @Test
    fun `a response larger than the cap is refused`() {
        val port = server { it.respond(200, ByteArray(4096) { 'a'.code.toByte() }) }
        val chunked = server { exchange ->
            exchange.sendResponseHeaders(200, 0)
            repeat(64) { exchange.responseBody.write(ByteArray(1024)) }
        }

        assertThatThrownBy { reader(maxBytes = 1024).readSource("http://127.0.0.1:$port/catalog.json", emptyMap(), allowHttp = true) }
            .isInstanceOf(OutboundFetchException::class.java)
            .hasMessageContaining("limit")
        assertThatThrownBy { reader(maxBytes = 1024).readSource("http://127.0.0.1:$chunked/catalog.json", emptyMap(), allowHttp = true) }
            .isInstanceOf(OutboundFetchException::class.java)
            .hasMessageContaining("limit")
    }

    @Test
    fun `an error status keeps its type but not the body`() {
        val port = server { it.respond(404, "internal secret page".toByteArray()) }

        assertThatThrownBy { reader().readSource("http://127.0.0.1:$port/catalog.json", emptyMap(), allowHttp = true) }
            .isInstanceOf(HttpClientErrorException.NotFound::class.java)
            .satisfies({ assertThat(it.message).doesNotContain("internal secret page") })
    }

    @Test
    fun `plain http is refused unless allowed`() {
        val port = server { it.respond(200, "{}".toByteArray()) }

        assertThatThrownBy { reader().readSource("http://127.0.0.1:$port/catalog.json", emptyMap(), allowHttp = false) }
            .isInstanceOf(OutboundUrlRejectedException::class.java)
    }
}

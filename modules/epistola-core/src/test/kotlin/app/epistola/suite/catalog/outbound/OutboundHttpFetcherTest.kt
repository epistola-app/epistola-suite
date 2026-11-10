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
import java.time.Duration
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
        responseTimeout: Duration = Duration.ofSeconds(60),
    ): OutboundSourceReader {
        val guard = OutboundUrlGuard(
            CatalogSourceProperties(allowHttp = true, outbound = CatalogSourceProperties.Outbound(allowPrivateNetworks = allowPrivate)),
            resolver,
        )
        return OutboundSourceReader(guard, OutboundHttpFetcher(guard, responseTimeout = responseTimeout), DefaultResourceLoader(), maxBytes)
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
    fun `a source answering a byte at a time is given up on once the response takes too long`() {
        // Each byte arrives well within the read timeout, so only a limit on the whole response ends
        // it. A raw socket, so nothing between the test and the client buffers the bytes.
        val socket = java.net.ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        val dripping = Thread.ofVirtual().start {
            runCatching {
                socket.accept().use { connection ->
                    connection.getInputStream().read(ByteArray(8192))
                    val out = connection.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n".toByteArray())
                    out.flush()
                    repeat(100) {
                        out.write('a'.code)
                        out.flush()
                        Thread.sleep(100)
                    }
                }
            }
        }

        try {
            val started = System.nanoTime()
            assertThatThrownBy { reader(responseTimeout = Duration.ofSeconds(1)).readSource("http://127.0.0.1:${socket.localPort}/catalog.json", emptyMap(), allowHttp = true) }
                .isInstanceOf(OutboundFetchException::class.java)
                .hasMessageContaining("too long")
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3))
        } finally {
            socket.close()
            dripping.interrupt()
        }
    }

    @Test
    fun `reads made within one budget stop once it is spent`() {
        val port = server { it.respond(200, ByteArray(600) { 'a'.code.toByte() }) }
        val reader = reader()

        OutboundBudget.within(1000, Duration.ofMinutes(1)) {
            assertThat(reader.readSource("http://127.0.0.1:$port/one.json", emptyMap(), allowHttp = true)).hasSize(600)
            assertThatThrownBy { reader.readSource("http://127.0.0.1:$port/two.json", emptyMap(), allowHttp = true) }
                .isInstanceOf(OutboundFetchException::class.java)
                .hasMessageContaining("in all")
        }
        // Outside it, each response is held to its own cap only.
        assertThat(reader.readSource("http://127.0.0.1:$port/three.json", emptyMap(), allowHttp = true)).hasSize(600)
    }

    @Test
    fun `no read starts once a budget's time is up`() {
        val port = server { it.respond(200, ByteArray(10)) }
        val reader = reader()

        OutboundBudget.within(1000, Duration.ZERO) {
            assertThatThrownBy { reader.readSource("http://127.0.0.1:$port/one.json", emptyMap(), allowHttp = true) }
                .isInstanceOf(OutboundFetchException::class.java)
                .hasMessageContaining("longer than it may")
        }
        assertThat(requests).isEmpty()
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

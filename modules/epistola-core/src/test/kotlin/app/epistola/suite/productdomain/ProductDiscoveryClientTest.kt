// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.productdomain

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration

class ProductDiscoveryClientTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private val client = ProductDiscoveryClient(Duration.ofSeconds(2), Duration.ofSeconds(2))
    private val url get() = "http://127.0.0.1:${server.address.port}/.well-known/epistola.json"
    private var seenHeaders: Map<String, String> = emptyMap()

    @AfterEach
    fun stop() = server.stop(0)

    private fun respond(status: Int, body: ByteArray) {
        server.createContext("/.well-known/epistola.json") { exchange ->
            seenHeaders = exchange.requestHeaders.mapValues { it.value.joinToString() }.mapKeys { it.key.lowercase() }
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    @Test
    fun `fetches the document anonymously`() {
        respond(200, """{"schemaVersion":1}""".toByteArray())

        assertThat(client.fetch(url).path("schemaVersion").intValue()).isEqualTo(1)
        assertThat(seenHeaders["user-agent"]).isEqualTo("epistola-suite")
        assertThat(seenHeaders.keys).noneMatch { it.startsWith("x-epistola") }
    }

    @Test
    fun `a non-200 answer or an oversized body is a failure, not a document`() {
        respond(404, "not here".toByteArray())
        assertThatThrownBy { client.fetch(url) }.isInstanceOf(ProductDiscoveryException::class.java).hasMessageContaining("404")
    }

    @Test
    fun `an oversized body is refused`() {
        respond(200, ByteArray(ProductDiscoveryClient.MAX_BYTES + 10) { ' '.code.toByte() })
        assertThatThrownBy { client.fetch(url) }.isInstanceOf(ProductDiscoveryException::class.java).hasMessageContaining("larger than")
    }
}

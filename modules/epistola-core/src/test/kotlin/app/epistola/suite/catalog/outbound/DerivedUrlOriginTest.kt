// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.outbound

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A manifest names its details and binaries by relative path only, so a catalog's credentials
 * never leave the manifest's origin. Two loopback servers stand in for the catalog host and a
 * host the manifest should not be able to reach.
 */
class DerivedUrlOriginTest {
    private val servers = mutableListOf<HttpServer>()
    private val seen = CopyOnWriteArrayList<String>()

    @AfterEach
    fun stop() = servers.forEach { it.stop(0) }

    private fun server(name: String): Int {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            seen += "$name ${exchange.requestURI.path} auth=${exchange.requestHeaders.getFirst("Authorization")}"
            val body = "{}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
            exchange.close()
        }
        server.start()
        servers += server
        return server.address.port
    }

    private fun reader(): OutboundSourceReader {
        val guard = OutboundUrlGuard(
            CatalogSourceProperties(allowHttp = true, outbound = CatalogSourceProperties.Outbound(allowPrivateNetworks = true)),
            HostResolver.SYSTEM,
        )
        return OutboundSourceReader(guard, OutboundHttpFetcher(guard), DefaultResourceLoader(), 1024 * 1024)
    }

    @Test
    fun `a derived URL on another origin is refused before any request`() {
        val catalogPort = server("catalog")
        val otherPort = server("other")
        val manifest = "http://127.0.0.1:$catalogPort/catalog/catalog.json"
        val credentials = mapOf("Authorization" to "Bearer SECRET")
        val reader = reader()

        listOf(
            "http://localhost:$otherPort/catalog/detail.json",
            "http://127.0.0.1:$otherPort/catalog/detail.json",
            "http://127.0.0.1:$catalogPort/elsewhere/detail.json",
            "http://127.0.0.1:$catalogPort/catalog/../elsewhere/detail.json",
        ).forEach { url ->
            assertThatThrownBy { reader.readDerived(url, manifest, credentials, allowHttp = true) }
                .describedAs(url)
                .isInstanceOf(OutboundUrlRejectedException::class.java)
        }
        assertThat(seen).isEmpty()
    }

    @Test
    fun `a derived URL below the manifest gets the credentials`() {
        val catalogPort = server("catalog")
        val manifest = "http://127.0.0.1:$catalogPort/catalog/catalog.json"

        reader().readDerived(
            "http://127.0.0.1:$catalogPort/catalog/resources/templates/t.json",
            manifest,
            mapOf("Authorization" to "Bearer SECRET"),
            allowHttp = true,
        )

        assertThat(seen).containsExactly("catalog /catalog/resources/templates/t.json auth=Bearer SECRET")
    }
}

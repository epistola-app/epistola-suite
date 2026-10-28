// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * The scheduled check of a catalog subscribed from a URL ([ManifestCatalogUpstreamProbe]) when its
 * source fails: each failure is classified, so the catalog shows why rather than a generic
 * protocol error, and the worker records it instead of logging it as a bug in the probe.
 */
class UrlSourceUpstreamCheckTest : IntegrationTestBase() {

    @Autowired private lateinit var probe: ManifestCatalogUpstreamProbe

    private var server: HttpServer? = null

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    /** A source answering `/catalog.json` with [status] and [body]; its manifest URL. */
    private fun source(status: Int, body: String = ""): String {
        val started = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        started.createContext("/") { exchange ->
            exchange.use {
                val bytes = body.toByteArray()
                it.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) it.responseBody.write(bytes)
            }
        }
        started.start()
        server = started
        return "http://127.0.0.1:${started.address.port}/catalog.json"
    }

    /** A catalog of a new tenant, subscribed from [sourceUrl]. */
    private fun subscribedTo(sourceUrl: String): Catalog {
        val tenant = createTenant("Upstream URL").id
        val catalog = withMediator {
            CreateCatalog(tenantKey = tenant, id = CatalogKey.of("url-upstream"), name = "URL upstream").execute()
            GetCatalog(testCatalog(tenant, "url-upstream")).query()!!
        }
        return catalog.copy(sourceUrl = sourceUrl)
    }

    private fun failureOf(catalog: Catalog): CatalogUpstreamCheckFailure? = catchThrowableOfType(CatalogUpstreamCheckException::class.java) { probe.probe(catalog) }?.failure

    @Test
    fun `a source that answers with a server error could not be reached`() {
        assertThat(failureOf(subscribedTo(source(503)))).isEqualTo(CatalogUpstreamCheckFailure.UNREACHABLE)
        assertThat(failureOf(subscribedTo(source(500)))).isEqualTo(CatalogUpstreamCheckFailure.UNREACHABLE)
    }

    @Test
    fun `a source that asks to slow down could not be reached`() {
        assertThat(failureOf(subscribedTo(source(429)))).isEqualTo(CatalogUpstreamCheckFailure.UNREACHABLE)
    }

    @Test
    fun `a source that no longer has the catalog says so`() {
        assertThat(failureOf(subscribedTo(source(404)))).isEqualTo(CatalogUpstreamCheckFailure.NOT_FOUND)
        assertThat(failureOf(subscribedTo(source(410)))).isEqualTo(CatalogUpstreamCheckFailure.NOT_FOUND)
    }

    @Test
    fun `a source that refuses the credentials is unauthorized`() {
        assertThat(failureOf(subscribedTo(source(401)))).isEqualTo(CatalogUpstreamCheckFailure.UNAUTHORIZED)
        assertThat(failureOf(subscribedTo(source(403)))).isEqualTo(CatalogUpstreamCheckFailure.UNAUTHORIZED)
    }

    @Test
    fun `a source that refuses the request otherwise answered with something unusable`() {
        assertThat(failureOf(subscribedTo(source(400)))).isEqualTo(CatalogUpstreamCheckFailure.PROTOCOL_ERROR)
    }

    @Test
    fun `a malformed manifest is a protocol error`() {
        assertThat(failureOf(subscribedTo(source(200, "{ this is not a manifest")))).isEqualTo(CatalogUpstreamCheckFailure.PROTOCOL_ERROR)
        assertThat(failureOf(subscribedTo(source(200, """{"schemaVersion": 8}""")))).isEqualTo(CatalogUpstreamCheckFailure.PROTOCOL_ERROR)
    }

    @Test
    fun `a source nobody listens on could not be reached`() {
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

        assertThat(failureOf(subscribedTo("http://127.0.0.1:$closedPort/catalog.json"))).isEqualTo(CatalogUpstreamCheckFailure.UNREACHABLE)
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.testing.FakeExchangeServer
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.HttpClientErrorException

/**
 * The consumer half of the Exchange client, over a real socket.
 *
 * These are the calls with something to get wrong that a type cannot catch: the archive route is a
 * path template the generated client hands back unexpanded, the next-page link is a URL rather
 * than the cursor it contains, and the archive is the one response big enough that reading all of
 * it is itself the hazard.
 */
class ExchangeCatalogClientTest : IntegrationTestBase() {

    @Autowired
    private lateinit var client: ExchangeClient

    private val archive = "PK pretend this is a catalog".toByteArray()

    @BeforeEach
    fun resetExchange() {
        exchange.reset()
        exchange.publish("acme", "invoices", archive, version = "1.0.0", name = "Invoices")
    }

    @Test
    fun `search maps a catalog into Suite's own terms`() {
        val page = client.searchCatalogs(exchange.baseUrl, TOKEN, query = "invoic", cursor = null, limit = 50)

        assertThat(page.items).singleElement().satisfies({ summary ->
            assertThat(summary.namespace).isEqualTo("acme")
            assertThat(summary.catalogKey).isEqualTo("invoices")
            assertThat(summary.name).isEqualTo("Invoices")
            assertThat(summary.organizationName).isEqualTo("Acme")
            assertThat(summary.visibility).isEqualTo("PUBLIC")
            assertThat(summary.latestVersion).isEqualTo("1.0.0")
            assertThat(summary.sourceUri).isEqualTo("exchange:acme/invoices")
        })
        assertThat(exchange.searchQueries).containsExactly("invoic")
    }

    @Test
    fun `the next page is kept as a cursor, not the URL it arrived in`() {
        exchange.nextSearchCursor = "opaque-cursor-value"

        val page = client.searchCatalogs(exchange.baseUrl, TOKEN, query = null, cursor = null, limit = 50)

        // Following the URL itself would let a response choose the host of the next request.
        assertThat(page.nextCursor).isEqualTo("opaque-cursor-value")
    }

    @Test
    fun `a single page reports no next cursor`() {
        val page = client.searchCatalogs(exchange.baseUrl, TOKEN, query = null, cursor = null, limit = 50)

        assertThat(page.nextCursor).isNull()
    }

    @Test
    fun `latestVersion is the newest release anyone may install`() {
        exchange.publish("acme", "invoices", archive, version = "2.0.0", availability = "WITHDRAWN")

        val summary = client.catalog(exchange.baseUrl, TOKEN, "acme", "invoices")

        assertThat(summary.latestVersion).isEqualTo("1.0.0")
    }

    @Test
    fun `a release that is not available is reported as such rather than hidden`() {
        // Exchange hides these from everyone except the installation that publishes the catalog,
        // which for a tenant that both publishes and installs is us. So the client must be able to
        // tell, rather than trusting that a returned release is installable.
        exchange.publish("acme", "invoices", archive, version = "2.0.0", availability = "WITHDRAWN")

        val releases = client.releases(exchange.baseUrl, TOKEN, "acme", "invoices")

        assertThat(releases.map { it.version }).containsExactly("2.0.0", "1.0.0")
        assertThat(releases.single { it.version == "2.0.0" }.installable).isFalse()
        assertThat(releases.single { it.version == "1.0.0" }.installable).isTrue()
    }

    @Test
    fun `the archive route's path template is expanded from the generated parameters`() {
        val bytes = client.downloadArchive(exchange.baseUrl, TOKEN, "acme", "invoices", "1.0.0", MAX_BYTES)

        assertThat(bytes).isEqualTo(archive)
        assertThat(exchange.archiveDownloads).containsExactly("acme/invoices@1.0.0")
    }

    @Test
    fun `an archive Exchange says is too large is refused before it is read`() {
        assertThatThrownBy {
            client.downloadArchive(exchange.baseUrl, TOKEN, "acme", "invoices", "1.0.0", maxBytes = 4)
        }.isInstanceOf(ExchangeArchiveTooLargeException::class.java)
            .hasMessageContaining("epistola.catalog.max-zip-size")
            // Only the declared-length path can name a size, so this is what says the body was
            // rejected on Exchange's own Content-Length rather than after being read.
            .hasMessageContaining("Exchange reported")
    }

    @Test
    fun `an archive larger than its own Content-Length claim is still refused`() {
        // The declared length is the server's claim about itself, so the read is capped as well.
        exchange.archiveResponse = { FakeExchangeServer.Response(200, "x".repeat(4096)) }

        assertThatThrownBy {
            client.downloadArchive(exchange.baseUrl, TOKEN, "acme", "invoices", "1.0.0", maxBytes = 8)
        }.isInstanceOf(ExchangeArchiveTooLargeException::class.java)
    }

    @Test
    fun `a refused download raises what the rest of the client raises`() {
        // Reading the body directly turns off default status handling, so without re-raising this
        // would arrive as an empty archive rather than something ExchangeFailure can classify.
        assertThatThrownBy {
            client.downloadArchive(exchange.baseUrl, TOKEN, "acme", "missing", "1.0.0", MAX_BYTES)
        }.isInstanceOf(HttpClientErrorException.NotFound::class.java)
    }

    companion object {
        private const val TOKEN = "access-token"
        private const val MAX_BYTES = 10L * 1024 * 1024
        private val exchange = FakeExchangeServer()

        @JvmStatic
        @DynamicPropertySource
        fun exchangeProperties(registry: DynamicPropertyRegistry) {
            registry.add("epistola.exchange.enabled") { "true" }
            registry.add("epistola.exchange.base-url") { exchange.baseUrl }
            registry.add("epistola.exchange.allow-http") { "true" }
        }

        @JvmStatic
        @AfterAll
        fun stopExchange() = exchange.close()
    }
}

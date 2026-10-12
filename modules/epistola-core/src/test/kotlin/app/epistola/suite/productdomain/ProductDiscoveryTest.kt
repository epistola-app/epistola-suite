// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.productdomain

import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.time.EpistolaClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@Isolated("Uses the global app_metadata product discovery status")
class ProductDiscoveryTest : IntegrationTestBase() {
    @Autowired
    private lateinit var metadata: AppMetadataService

    private val domain = ProductDomainProperties()
    private val defaults = ProductEndpoints.defaults(domain)
    private val start = Instant.parse("2026-10-12T08:00:00Z")

    /** Answers with [document], or fails when it is null; counts calls. */
    private inner class FakeClient(var document: String?) : ProductDiscoveryClient(Duration.ofSeconds(1), Duration.ofSeconds(1)) {
        var calls = 0
        val urls = mutableListOf<String>()

        override fun fetch(url: String): JsonNode {
            calls++
            urls += url
            return JsonMapper.builder().build().readTree(document ?: throw ProductDiscoveryException("connection refused"))
        }
    }

    private val good = """{ "schemaVersion": 1, "versionCheck": { "endpoint": "https://epistola.app/releases.json" } }"""

    @BeforeEach
    @AfterEach
    fun reset() = metadata.setAs(ProductDiscovery.STATUS_KEY, ProductDiscoveryStatus())

    private fun <T> at(instant: Instant, block: () -> T): T = EpistolaClock.withClock(Clock.fixed(instant, ZoneOffset.UTC)) { block() }

    @Test
    fun `the first request fetches the document from the product domain, and the copy is shared`() {
        val client = FakeClient(good)
        val discovery = ProductDiscovery(domain, metadata, client)

        val endpoints = at(start) { discovery.endpoints() }
        val onAnotherNode = at(start.plusSeconds(60)) { ProductDiscovery(domain, metadata, FakeClient(null)).endpoints() }

        assertThat(client.urls).containsExactly("https://epistola.app/.well-known/epistola.json")
        assertThat(endpoints.versionCheck).isEqualTo("https://epistola.app/releases.json")
        assertThat(onAnotherNode.versionCheck).isEqualTo("https://epistola.app/releases.json")
    }

    @Test
    fun `a good copy is refreshed after a day, not before`() {
        val client = FakeClient(good)
        val discovery = ProductDiscovery(domain, metadata, client)

        at(start) { discovery.endpoints() }
        at(start.plus(Duration.ofHours(23))) { discovery.endpoints() }
        assertThat(client.calls).isEqualTo(1)

        at(start.plus(Duration.ofHours(24))) { discovery.endpoints() }
        assertThat(client.calls).isEqualTo(2)
    }

    @Test
    fun `when epistola_app is unreachable and nothing was cached, the built-in defaults apply`() {
        val client = FakeClient(null)

        val endpoints = at(start) { ProductDiscovery(domain, metadata, client).endpoints() }

        assertThat(endpoints).isEqualTo(defaults)
    }

    @Test
    fun `a failed refresh keeps the last good copy and retries after an hour`() {
        val client = FakeClient(good)
        val discovery = ProductDiscovery(domain, metadata, client)
        at(start) { discovery.endpoints() }

        client.document = null
        val afterFailure = at(start.plus(Duration.ofDays(1))) { discovery.endpoints() }
        at(start.plus(Duration.ofDays(1)).plus(Duration.ofMinutes(59))) { discovery.endpoints() }
        val callsBeforeRetry = client.calls
        at(start.plus(Duration.ofDays(1)).plus(Duration.ofHours(1))) { discovery.endpoints() }

        assertThat(afterFailure.versionCheck).isEqualTo("https://epistola.app/releases.json")
        assertThat(discovery.status()?.lastError).contains("connection refused")
        assertThat(callsBeforeRetry).isEqualTo(2)
        assertThat(client.calls).isEqualTo(3)
    }

    @Test
    fun `a copy cached for another product domain is not used`() {
        at(start) { ProductDiscovery(domain, metadata, FakeClient(good)).endpoints() }
        val staging = ProductDomainProperties("staging.epistola.app")
        val client = FakeClient(null)

        val endpoints = at(start.plusSeconds(60)) { ProductDiscovery(staging, metadata, client).endpoints() }

        assertThat(client.urls).containsExactly("https://staging.epistola.app/.well-known/epistola.json")
        assertThat(endpoints).isEqualTo(ProductEndpoints.defaults(staging))
    }

    @Test
    fun `reading the cached endpoints never calls out`() {
        val client = FakeClient(good)

        val endpoints = at(start) { ProductDiscovery(domain, metadata, client).cachedEndpoints() }

        assertThat(client.calls).isZero()
        assertThat(endpoints).isEqualTo(defaults)
    }
}

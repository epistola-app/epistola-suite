// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.testing.FakeExchangeServer
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.SharedFakeExchange
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Integration tests against the JVM-wide [SharedFakeExchange], sharing one Spring context.
 *
 * The fake is scripted through mutable fields, so subclasses hold the same resource lock and
 * never run concurrently with each other (they still run alongside everything else), and the
 * fake is reset before every test. Subclasses that need other Exchange properties (discovery
 * instead of a base URL, plaintext refused, a retry budget) get their own context; see
 * [ExchangeDiscoveryIntegrationTestBase] for the discovery variant.
 */
@ResourceLock(SharedFakeExchange.RESOURCE_LOCK)
abstract class ExchangeIntegrationTestBase : IntegrationTestBase() {
    protected val exchange: FakeExchangeServer
        get() = SharedFakeExchange.server

    @Autowired
    private lateinit var jdbi: Jdbi

    /**
     * The publication outbox is installation-wide, and these classes share one database. Each
     * test starts from an empty queue, as it did when every class had a database of its own.
     */
    @BeforeEach
    fun purgeSharedExchangeState() {
        exchange.reset()
        jdbi.useHandle<Exception> { handle ->
            handle.execute("DELETE FROM catalog_release_publication_archives")
            handle.execute("DELETE FROM catalog_release_publications")
        }
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun exchangeProperties(registry: DynamicPropertyRegistry) {
            registry.add("epistola.exchange.enabled") { "true" }
            registry.add("epistola.exchange.base-url") { SharedFakeExchange.server.baseUrl }
            // The loopback stand-in is plaintext.
            registry.add("epistola.exchange.allow-http") { "true" }
        }
    }
}

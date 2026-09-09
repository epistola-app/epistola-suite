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
 * The discovery-path counterpart of [ExchangeIntegrationTestBase]: Exchange is configured through
 * its discovery document rather than a base URL. Same shared fake, same lock, its own context.
 */
@ResourceLock(SharedFakeExchange.RESOURCE_LOCK)
abstract class ExchangeDiscoveryIntegrationTestBase : IntegrationTestBase() {
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
            // Deliberately no base-url: this is the discovery path.
            registry.add("epistola.exchange.discovery-url") { "${SharedFakeExchange.server.baseUrl}/.well-known/epistola/exchange.json" }
            registry.add("epistola.exchange.allow-http") { "true" }
        }
    }
}

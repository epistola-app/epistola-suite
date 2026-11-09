// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.support.connection.SupportConnection
import app.epistola.suite.support.connection.SupportConnectionCheckScheduler
import app.epistola.suite.support.connection.SupportConnectionState
import app.epistola.suite.support.connection.SupportTokenRefreshScheduler
import app.epistola.suite.support.requests.adapters.RestSupportRequestSyncAdapter
import app.epistola.suite.support.requests.sync.SupportRequestPollScheduler
import app.epistola.suite.support.requests.sync.SupportRequestSyncPort
import app.epistola.suite.testing.FakeSupportServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Boots the full app with the **support connection enabled**, pointed at a stand-in support
 * service, and asserts the context loads with exactly one bean per sync port and one support
 * connection. Regression guard for the `@ConditionalOnMissingBean` ordering bug that once
 * registered both a no-op and a remote adapter and failed startup with "a single bean, but 2 were
 * found". Nothing is registered at startup: the scheduled check does that.
 */
class SupportTierContextTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var supportRequestSyncPorts: List<SupportRequestSyncPort>

    @Autowired
    private lateinit var connections: List<SupportConnection>

    @Autowired
    private lateinit var checkScheduler: List<SupportConnectionCheckScheduler>

    @Autowired
    private lateinit var tokenScheduler: List<SupportTokenRefreshScheduler>

    @Autowired
    private lateinit var pollScheduler: ObjectProvider<SupportRequestPollScheduler>

    @Test
    fun `replies from Epistola are read by default`() {
        assertThat(pollScheduler.ifAvailable).`as`("the inbound feedback poll runs without extra configuration").isNotNull()
    }

    @Test
    fun `the support connection wires once, with exactly one support request sync port`() {
        assertThat(supportRequestSyncPorts).hasSize(1)
        assertThat(supportRequestSyncPorts.single()).`as`("the REST adapter replaced the no-op").isInstanceOf(RestSupportRequestSyncAdapter::class.java)
        assertThat(connections).hasSize(1)
        assertThat(checkScheduler).hasSize(1)
        assertThat(tokenScheduler).hasSize(1)
        assertThat(connections.single().status().state).isNotEqualTo(SupportConnectionState.Disabled)
    }

    companion object {
        private val support = FakeSupportServer()

        @JvmStatic
        @DynamicPropertySource
        fun supportService(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.enabled") { "true" }
            registry.add("epistola.support.privacy.tenant-identity") { "named" }
            // Every service selected, as an administrator would; the selection itself is tested in SupportServiceSelectionIntegrationTest.
            registry.add("epistola.support.services") { "telemetry,requests,mirror" }
            registry.add("epistola.support.endpoint") { support.baseUrl }
            registry.add("epistola.support.allow-http") { "true" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = support.close()
    }
}

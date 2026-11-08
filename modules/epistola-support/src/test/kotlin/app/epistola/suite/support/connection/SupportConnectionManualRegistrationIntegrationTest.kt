// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.FakeSupportServer
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * The support connection without `epistola.support.auto-register` (the default): nothing leaves the
 * installation until an installation administrator registers, and a claim code that expires unlinked
 * is recorded, not replaced on its own.
 */
class SupportConnectionManualRegistrationIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var connection: DefaultSupportConnection

    @Autowired
    private lateinit var store: SupportConnectionStore

    @BeforeEach
    fun forgetRegistration() {
        store.forget()
        connection.invalidate()
    }

    @Test
    fun `the check never registers on its own, nor contacts the support service`() {
        val before = support.registrationRequests.size
        val requestsBefore = support.requests.size

        connection.checkNow()
        connection.checkNow()

        assertThat(support.requests).`as`("nothing leaves the installation before an administrator registers").hasSize(requestsBefore)

        assertThat(connection.status().state).isEqualTo(SupportConnectionState.Unregistered)
        assertThat(connection.status().checkedAt).`as`("the check still ran").isNotNull()
        assertThat(connection.status().lastError).isNull()
        assertThat(support.registrationRequests).hasSize(before)
        assertThat(store.credentials()).isNull()
        val overview = withMediator { GetSupportConnectionOverview().query() }
        assertThat(overview.canRegister).isTrue()
        assertThat(overview.autoRegister).isFalse()
    }

    @Test
    fun `an installation administrator registers, only while not registered`() {
        withMediator { RegisterSupportConnection().execute() }

        val pending = connection.status().state as SupportConnectionState.Pending
        assertThat(pending.claimCode).isEqualTo(support.registrations.getValue(pending.clientId).claimCode)
        assertThat(pending.claimCodeExpiresAt).isEqualTo(support.registrations.getValue(pending.clientId).claimCodeExpiresAt)
        assertThat(withMediator { GetSupportConnectionOverview().query() }.canRegister).isFalse()
        assertThatThrownBy { withMediator { RegisterSupportConnection().execute() } }
            .isInstanceOf(SupportRegistrationNotAllowedException::class.java)

        // Once registered, the scheduled check carries on with the registration as before.
        support.activate(pending.clientId)
        connection.checkNow()
        assertThat((connection.status().state as SupportConnectionState.Active).clientId).isEqualTo(pending.clientId)
    }

    @Test
    fun `a claim code that expires unlinked is recorded with its date, and the check does not register again`() {
        withMediator { RegisterSupportConnection().execute() }
        val first = connection.status().state as SupportConnectionState.Pending
        val expiresAt = checkNotNull(first.claimCodeExpiresAt)

        support.expire(first.clientId)
        // Only once the code expired does invalid_client mean the registration is gone.
        testClock.advanceBy(java.time.Duration.between(app.epistola.suite.time.EpistolaClock.instant(), expiresAt).plusMinutes(1))
        connection.checkNow()

        val expired = connection.status().state as SupportConnectionState.Expired
        assertThat(expired.clientId).isEqualTo(first.clientId)
        assertThat(expired.claimCodeExpiresAt).isEqualTo(expiresAt)
        assertThat(store.credentials()).`as`("the gone registration's key is forgotten").isNull()
        val before = support.registrationRequests.size
        connection.checkNow()
        assertThat(connection.status().state).isEqualTo(expired)
        assertThat(support.registrationRequests).hasSize(before)
        val overview = withMediator { GetSupportConnectionOverview().query() }
        assertThat(overview.canRegister).isTrue()
        assertThat(overview.canRegisterAgain).isFalse()
        val tenant = createTenant("Expired support")
        assertThat(withMediator { GetTenantSupportSummary(tenant.id).query() }.connection).isEqualTo(TenantSupportConnection.NOT_CONNECTED)

        withMediator { RegisterSupportConnection().execute() }

        val again = connection.status().state as SupportConnectionState.Pending
        assertThat(again.clientId).isNotEqualTo(first.clientId)
        assertThat(again.replacedExpiredAt).`as`("an administrator registered; nothing was replaced on its own").isNull()
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

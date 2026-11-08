// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** With the support connection off (the default) the bean exists, says so, and never calls out. */
class SupportConnectionDisabledIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var connection: SupportConnection

    @Autowired
    private lateinit var store: SupportConnectionStore

    @Autowired
    private lateinit var handlers: List<ClusterScheduledTaskHandler>

    @Test
    fun `the connection is disabled, registers nothing and offers no token`() {
        connection.checkNow()

        assertThat(connection.status()).isEqualTo(SupportConnectionStatus(SupportConnectionState.Disabled, null, null, null))
        assertThat(store.credentials()).isNull()
        assertThat(connection.hasScope(SupportScope.REQUESTS)).isFalse()
        assertThat(connection.cachedAccessToken()).isNull()
        assertThat(connection.apiEndpoint()).isNull()
        assertThatThrownBy { connection.accessToken() }.isInstanceOf(SupportUnavailableException::class.java)
        assertThat(connection.lastContactFailed()).isFalse()
    }

    @Test
    fun `no scheduled task talks to the support service`() {
        assertThat(handlers.map { it.taskType }).doesNotContain(SupportConnectionCheckScheduler.TASK_TYPE, SupportTokenRefreshScheduler.TASK_TYPE)
    }

    @Test
    fun `the pages say the connection is off and offer nothing to do`() {
        val overview = withMediator { GetSupportConnectionOverview().query() }
        assertThat(overview.state).isEqualTo(SupportConnectionState.Disabled)
        assertThat(overview.canCheck).isFalse()
        assertThat(overview.canRegisterAgain).isFalse()
        assertThat(overview.services).noneMatch { it.granted }

        val tenant = createTenant("Support off")
        assertThat(withMediator { GetTenantSupportSummary(tenant.id).query() }.connection).isEqualTo(TenantSupportConnection.OFF)

        assertThat(withMediator { CheckSupportConnection().execute() }.state).isEqualTo(SupportConnectionState.Disabled)
        assertThatThrownBy { withMediator { RegisterSupportConnectionAgain(confirmed = true).execute() } }
            .isInstanceOf(SupportRegistrationNotAllowedException::class.java)
    }
}

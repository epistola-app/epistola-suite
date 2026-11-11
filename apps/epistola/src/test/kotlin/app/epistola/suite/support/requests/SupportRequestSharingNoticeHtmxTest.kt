// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests

import app.epistola.suite.mediator.execute
import app.epistola.suite.support.AutoRegisteringSupportTestBase
import app.epistola.suite.support.connection.DefaultSupportConnection
import app.epistola.suite.support.connection.EnsureConfiguredSupportConnection
import app.epistola.suite.support.connection.SupportConnectionStore
import app.epistola.suite.support.requests.consent.GrantSupportRequestSyncConsent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus

/**
 * The tenant's Support page is the one place for what it shares, support requests included: a tenant
 * that shares them sees there why nothing is sent yet, against a stand-in support service.
 */
class SupportRequestSharingNoticeHtmxTest : AutoRegisteringSupportTestBase() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var store: SupportConnectionStore

    @Autowired
    private lateinit var connection: DefaultSupportConnection

    @BeforeEach
    fun forget() {
        store.forget()
        connection.invalidate()
    }

    private fun supportPage(tenantKey: String): String = restTemplate.getForEntity("/tenants/$tenantKey/support", String::class.java).let {
        assertThat(it.statusCode).isEqualTo(HttpStatus.OK)
        it.body!!
    }

    private fun notice(page: String): String? = Regex("data-testid=\"support-sharing-notice-requests\">([^<]*)<").find(page)?.groupValues?.get(1)

    @Test
    fun `a tenant that shares support requests sees on its Support page why they are not sent yet, until they are`() {
        val tenant = createTenant("Sharing Notice")
        connection.checkNow()
        withMediator { GrantSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        assertThat(notice(supportPage(tenant.id.value))).contains("waits to be activated")

        support.activate(scopes = setOf("telemetry"))
        connection.checkNow()
        assertThat(notice(supportPage(tenant.id.value))).contains("does not grant this installation the support request service")

        support.activate(scopes = setOf("requests"))
        connection.checkNow()
        assertThat(notice(supportPage(tenant.id.value))).`as`("sent: nothing to explain").isNull()
    }

    @Test
    fun `there is no separate support request sharing page`() {
        val tenant = createTenant("Sharing No Page")
        connection.checkNow()

        assertThat(supportPage(tenant.id.value)).doesNotContain("support/request-sharing").doesNotContain("Support request sharing")
        assertThat(restTemplate.getForEntity("/tenants/${tenant.id.value}/support/request-sharing", String::class.java).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }
}

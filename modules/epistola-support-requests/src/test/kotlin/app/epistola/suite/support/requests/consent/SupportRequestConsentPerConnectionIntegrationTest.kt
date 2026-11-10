// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.consent

import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.common.ids.SupportRequestKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.connection.EnsureConfiguredSupportConnection
import app.epistola.suite.support.connection.SupportConnectionRegistry
import app.epistola.suite.support.connection.SupportConnections
import app.epistola.suite.support.requests.RecordingSupportRequestSyncPort
import app.epistola.suite.support.requests.SupportRequestCategory
import app.epistola.suite.support.requests.SupportRequestPriority
import app.epistola.suite.support.requests.commands.CreateSupportRequest
import app.epistola.suite.support.requests.sync.SupportRequestSyncPort
import app.epistola.suite.support.sharing.SetSharingRestriction
import app.epistola.suite.support.sharing.SharingRestriction
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID

/**
 * A tenant shares support requests with each support connection on its own (ADR 0053 step 3): its
 * consent, the installation default and the restriction are per connection, and one connection's
 * never changes another's.
 */
@Isolated("Installation defaults and restrictions apply to every tenant")
@Import(SupportRequestConsentPerConnectionIntegrationTest.Config::class)
class SupportRequestConsentPerConnectionIntegrationTest : IntegrationTestBase() {
    @TestConfiguration
    class Config {
        /** A wired sync target, so a request written under consent carries the grant it was written under. */
        @Bean
        @Primary
        fun recordingSupportRequestSyncPort(): SupportRequestSyncPort = RecordingSupportRequestSyncPort()
    }

    @Autowired
    private lateinit var registry: SupportConnectionRegistry

    @Autowired
    private lateinit var jdbi: Jdbi

    private val epistola get() = withMediator { EnsureConfiguredSupportConnection().execute() }
    private val author = UserKey.of("00000000-0000-0000-0000-c0e5e1700001")

    private val partner: UUID get() = registry.tokenConnections().single { it.endpoint.toString() == PARTNER }.id

    @BeforeEach
    fun author() = ensureUser(author, "consent-per-connection", "consent-per-connection@epistola.test", "Per Connection")

    @AfterEach
    fun clear() {
        withMediator {
            for (connection in listOf(epistola, partner)) {
                SetSharingRestriction(REQUESTS_SECTION, null, connection).execute()
                ForgetSupportRequestSharing(connection).execute()
            }
        }
    }

    @Test
    fun `consent given to the partner leaves Epistola support's as it is, and the other way round`() {
        val tenant = createTenant("Consent Per Connection").id

        withMediator { GrantSupportRequestSyncConsent(tenant, partner).execute() }

        assertThat(withMediator { GetSupportRequestSyncConsent(tenant, partner).query() }.inForce).isTrue()
        assertThat(withMediator { GetSupportRequestSyncConsent(tenant, epistola).query() }.inForce).isFalse()

        withMediator { GrantSupportRequestSyncConsent(tenant, epistola).execute() }
        withMediator { WithdrawSupportRequestSyncConsent(tenant, epistola).execute() }

        assertThat(withMediator { GetSupportRequestSyncConsent(tenant, partner).query() }.inForce).`as`("the partner's stays").isTrue()
    }

    @Test
    fun `a default for the partner applies to its followers and to new tenants, not to Epistola support`() {
        val existing = createTenant("Default Per Connection").id

        withMediator { SetSupportRequestSharingDefault(share = true, connectionId = partner).execute() }
        val created = createTenant("Default Per Connection New").id

        for (tenant in listOf(existing, created)) {
            assertThat(withMediator { GetSupportRequestSyncConsent(tenant, partner).query() }).`as`("$tenant with the partner").matches { it.inForce && it.followsDefault }
            assertThat(withMediator { GetSupportRequestSyncConsent(tenant, epistola).query() }.inForce).`as`("$tenant with Epistola").isFalse()
        }
        assertThat(withMediator { GetSupportRequestSharingDefault(partner).query() }.share).isTrue()
        assertThat(withMediator { GetSupportRequestSharingDefault(epistola).query() }.isSet).isFalse()
    }

    @Test
    fun `not allowed for the partner refuses consent to it only`() {
        val tenant = createTenant("Restriction Per Connection").id
        withMediator { SetSharingRestriction(REQUESTS_SECTION, SharingRestriction.NOT_ALLOWED, partner).execute() }

        assertThatThrownBy { withMediator { GrantSupportRequestSyncConsent(tenant, partner).execute() } }.isNotNull()
        withMediator { GrantSupportRequestSyncConsent(tenant, epistola).execute() }

        assertThat(withMediator { GetSupportRequestSyncConsent(tenant, epistola).query() }.inForce).isTrue()
        assertThat(withMediator { GetSupportRequestSharingSettings(tenant, partner).query() }.notAllowed).isTrue()
        assertThat(withMediator { GetSupportRequestSharingSettings(tenant, epistola).query() }.notAllowed).isFalse()
    }

    @Test
    fun `withdrawing from the partner leaves what is still to go to Epistola support`() {
        val tenant = createTenant("Withdraw Per Connection").id
        withMediator { GrantSupportRequestSyncConsent(tenant, epistola).execute() }
        withMediator { GrantSupportRequestSyncConsent(tenant, partner).execute() }
        val request = SupportRequestId(SupportRequestKey.generate(), TenantId(tenant))
        withMediator {
            CreateSupportRequest(request, "Blank page", "It stays blank", SupportRequestCategory.BUG, SupportRequestPriority.MEDIUM, null, null, null, author).execute()
        }
        // Handed to both while the tenant shares with both: the question is in each one's ledger.
        jdbi.useHandle<Exception> { handle ->
            SupportRequestLedger.recordHistory(handle, tenant, request.key.value, epistola)
            SupportRequestLedger.recordHistory(handle, tenant, request.key.value, partner)
        }

        withMediator { WithdrawSupportRequestSyncConsent(tenant, partner).execute() }

        assertThat(stillToGo(request, epistola)).`as`("still to go to Epistola support").isEqualTo(1)
        assertThat(stillToGo(request, partner)).`as`("dropped for the partner").isZero()
    }

    private fun stillToGo(request: SupportRequestId, connection: UUID): Int = jdbi.withHandle<Int, Exception> {
        it.createQuery("SELECT COUNT(*) FROM support_request_delivered_items WHERE tenant_key = :t AND request_id = :r AND connection_id = :c AND remote_id IS NULL AND sent_at IS NULL")
            .bind("t", request.tenantKey).bind("r", request.key.value).bind("c", connection).mapTo(Int::class.java).one()
    }

    companion object {
        private const val PARTNER = "https://desk.partner.example"

        @JvmStatic
        @DynamicPropertySource
        fun partnerConnection(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.connections[0].name") { "Partner" }
            registry.add("epistola.support.connections[0].endpoint") { PARTNER }
            registry.add("epistola.support.connections[0].token") { "partner-token" }
            registry.add("epistola.support.connections[0].services") { "requests" }
        }
    }
}

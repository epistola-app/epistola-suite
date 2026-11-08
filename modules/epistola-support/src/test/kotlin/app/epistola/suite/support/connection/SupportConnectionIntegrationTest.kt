// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.encryption.VerifyNoStaleKeyIds
import app.epistola.suite.installation.InstallationService
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.FakeSupportResponse
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
import tools.jackson.databind.JsonNode
import java.net.URI
import java.time.Duration

/**
 * The support connection against a stand-in support service over real HTTP: registration, the
 * claim, tokens signed with the installation's key, refusal and registering again
 * (`docs/support-protocol.md`).
 */
class SupportConnectionIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var connection: DefaultSupportConnection

    @Autowired
    private lateinit var store: SupportConnectionStore

    @Autowired
    private lateinit var installations: InstallationService

    @Autowired
    private lateinit var jdbi: org.jdbi.v3.core.Jdbi

    @Autowired
    private lateinit var checkScheduler: SupportConnectionCheckScheduler

    @Autowired
    private lateinit var tokenScheduler: SupportTokenRefreshScheduler

    private val checkDefinition get() = checkScheduler.supportConnectionCheckTaskDefinition()
    private val tokenDefinition get() = tokenScheduler.supportTokenRefreshTaskDefinition()

    @BeforeEach
    fun forgetRegistration() {
        store.forget()
        connection.invalidate()
        support.registrationResponse = null
        support.tokenResponse = null
        support.metadataIssuer = null
    }

    @Test
    fun `an enabled installation registers, waits for its claim, and becomes active`() {
        assertThat(connection.status().state).isEqualTo(SupportConnectionState.Unregistered)

        connection.checkNow()

        val pending = connection.status().state as SupportConnectionState.Pending
        val registration = support.registrations.getValue(pending.clientId)
        assertThat(pending.claimCode).isEqualTo(registration.claimCode).matches("[2-9A-Z]{4}-[2-9A-Z]{4}")
        assertThat(connection.status().issuer).isEqualTo(URI(support.baseUrl))
        assertThat(connection.status().lastError).isNull()
        assertThat(connection.hasScope(SupportScope.REQUESTS)).isFalse()

        val request = support.registrationRequests.last()
        assertThat(request.path("epistola_installation_id").stringValue()).isEqualTo(installations.get().id.toString())
        assertThat(request.path("software_id").stringValue()).isEqualTo("epistola-suite")
        assertThat(request.path("token_endpoint_auth_method").stringValue()).isEqualTo("private_key_jwt")
        assertThat(request.path("grant_types").toList().map(JsonNode::stringValue)).containsExactly("client_credentials")
        val keys = request.path("jwks").path("keys")
        assertThat(keys.size()).isEqualTo(1)
        assertThat(keys[0].has("d")).`as`("the private key never leaves the installation").isFalse()
        assertThat(keys[0].path("kid").stringValue()).isNotBlank()

        val registrationsBefore = support.registrationRequests.size
        support.activate(scopes = setOf("telemetry", "requests"))
        connection.checkNow()

        val active = connection.status().state as SupportConnectionState.Active
        assertThat(active.clientId).isEqualTo(pending.clientId)
        assertThat(active.scopes).containsExactlyInAnyOrder(SupportScope.TELEMETRY, SupportScope.REQUESTS)
        assertThat(connection.hasScope(SupportScope.REQUESTS)).isTrue()
        assertThat(connection.hasScope(SupportScope.MIRROR)).isFalse()
        assertThat(connection.apiEndpoint()).isEqualTo(URI(support.apiEndpoint))
        assertThat(connection.otlpEndpoint()).isEqualTo(URI(support.otlpEndpoint))
        assertThat(connection.cachedAccessToken()).isNotNull()
        val tenant = createTenant("Support summary")
        val summary = withMediator { GetTenantSupportSummary(tenant.id).query() }
        assertThat(summary.connection).isEqualTo(TenantSupportConnection.CONNECTED)
        assertThat(summary.services.filter { it.granted }.map { it.scope }).containsExactlyInAnyOrder(SupportScope.TELEMETRY, SupportScope.REQUESTS)
        val overview = withMediator { GetSupportConnectionOverview().query() }
        assertThat(overview.state).isEqualTo(active)
        assertThat(overview.canCheck).isTrue()
        assertThat(overview.services.single { it.scope == SupportScope.MIRROR }.granted).isFalse()
        // The second check reused the stored registration.
        assertThat(support.registrationRequests).hasSize(registrationsBefore)
    }

    @Test
    fun `one node checks on the configured interval, every node keeps its own token`() {
        assertThat(checkDefinition.executionScope).isEqualTo(ClusterScheduledTaskExecutionScope.SINGLE_OWNER)
        assertThat(checkDefinition.schedule).isEqualTo(ClusterScheduledTaskSchedule.FixedDelay(Duration.ofMinutes(5).toMillis()))
        assertThat(tokenDefinition.executionScope).isEqualTo(ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE)

        connection.refreshTokenIfDue()
        assertThat(connection.cachedAccessToken()).`as`("no token before registering").isNull()

        connection.checkNow()
        support.activate()
        testClock.advanceBy(Duration.ofSeconds(support.tokenLifetimeSeconds))
        assertThat(connection.cachedAccessToken()).`as`("expired").isNull()
        connection.refreshTokenIfDue()
        assertThat(connection.cachedAccessToken()).isNotNull()
    }

    @Test
    fun `the stored registration is encrypted and covered by key rotation`() {
        connection.checkNow()

        val raw = jdbi.withHandle<String, Exception> {
            it.createQuery("SELECT credentials FROM support_connections WHERE id = :id").bind("id", store.connectionId).mapTo(String::class.java).one()
        }
        assertThat(raw).startsWith("enc:").doesNotContain("privateJwk")
        val report = withMediator { VerifyNoStaleKeyIds().query() }
        assertThat(report.keyIdsInUse).isNotEmpty()
        assertThat(store.credentials()!!.privateJwk).contains("\"d\"")
    }

    @Test
    fun `the rest client sends a bearer token and fetches a new one after a 401`() {
        connection.checkNow()
        support.activate()
        connection.checkNow()
        val clientId = (connection.status().state as SupportConnectionState.Active).clientId

        assertThat(connection.restClient().get().uri("/connection").retrieve().body(String::class.java)).contains(clientId)
        val first = support.bearerTokensSeen.last().second

        support.expireTokens()
        assertThat(connection.restClient().get().uri("/connection").retrieve().body(String::class.java)).contains(clientId)

        val seen = support.bearerTokensSeen.filter { it.first == "/api/v1/connection" }.map { it.second }.takeLast(2)
        assertThat(seen.first()).isEqualTo(first)
        assertThat(seen.last()).isNotEqualTo(first)
        assertThat(support.issuedTokens).containsKey(seen.last())
    }

    @Test
    fun `the rest client refuses to send anywhere but the support service`() {
        connection.checkNow()

        assertThatThrownBy { connection.restClient().get().uri("http://127.0.0.1:1/elsewhere").retrieve().toBodilessEntity() }
            .hasMessageContaining("only")
    }

    @Test
    fun `a revoked registration is refused, and registering again starts over with a new key`() {
        connection.checkNow()
        val first = connection.status().state as SupportConnectionState.Pending
        val firstKey = store.credentials()!!.privateJwk
        support.activate(first.clientId)
        connection.checkNow()

        // Revoking an activated registration is a refusal, never an expiry: it is not replaced on its own.
        val registrationsBefore = support.registrationRequests.size
        support.revoke(first.clientId)
        connection.checkNow()
        connection.checkNow()
        assertThat(support.registrationRequests).hasSize(registrationsBefore)
        assertThat(store.credentials()!!.clientId).isEqualTo(first.clientId)

        val refused = connection.status().state as SupportConnectionState.Refused
        assertThat(refused.clientId).isEqualTo(first.clientId)
        assertThat(refused.reason).contains("invalid_client")
        // Asking again would only get invalid_client again: every caller (a mirror round, a feedback
        // sweep) fails at once until a check or Register again.
        val tokenRequests = support.requests.count { it.method == "POST" && it.path == "/oauth2/token" }
        assertThatThrownBy { connection.accessToken() }.isInstanceOf(SupportRefusedException::class.java)
        assertThatThrownBy { connection.accessToken() }.isInstanceOf(SupportRefusedException::class.java)
        assertThat(support.requests.count { it.method == "POST" && it.path == "/oauth2/token" }).isEqualTo(tokenRequests)
        assertThat(connection.cachedAccessToken()).isNull()

        val overview = withMediator { GetSupportConnectionOverview().query() }
        assertThat(overview.canRegisterAgain).isTrue()
        assertThat(overview.registerAgainNeedsConfirm).isFalse()

        withMediator { RegisterSupportConnectionAgain().execute() }

        val again = connection.status().state as SupportConnectionState.Pending
        assertThat(again.clientId).isNotEqualTo(first.clientId)
        assertThat(again.claimCode).isNotBlank()
        assertThat(store.credentials()!!.privateJwk).isNotEqualTo(firstKey)
    }

    @Test
    fun `a pending registration keeps when its claim code expires`() {
        connection.checkNow()

        val pending = connection.status().state as SupportConnectionState.Pending
        assertThat(pending.claimCodeExpiresAt).isEqualTo(support.registrations.getValue(pending.clientId).claimCodeExpiresAt)
        assertThat(pending.replacedExpiredAt).isNull()

        connection.checkNow()
        assertThat((connection.status().state as SupportConnectionState.Pending).claimCodeExpiresAt).`as`("kept by the next check").isEqualTo(pending.claimCodeExpiresAt)
    }

    @Test
    fun `a registration nobody activated in time is gone, and the check registers again at once with a new key`() {
        connection.checkNow()
        val first = connection.status().state as SupportConnectionState.Pending
        val firstKey = store.credentials()!!.privateJwk

        support.expire(first.clientId)
        // The claim code expired: from then on, invalid_client means the registration is gone (before
        // it, a clock-skewed or removed registration is not forgotten).
        testClock.advanceBy(Duration.between(app.epistola.suite.time.EpistolaClock.instant(), first.claimCodeExpiresAt!!).plusMinutes(1))
        // A node refreshing its token learns it first: that is no refusal, and the state stays pending.
        connection.refreshTokenIfDue()
        assertThat(connection.status().state).isInstanceOf(SupportConnectionState.Pending::class.java)

        connection.checkNow()

        val again = connection.status().state as SupportConnectionState.Pending
        assertThat(again.clientId).isNotEqualTo(first.clientId)
        assertThat(again.claimCode).isEqualTo(support.registrations.getValue(again.clientId).claimCode)
        assertThat(again.replacedExpiredAt).`as`("the page says the previous code expired").isNotNull()
        assertThat(store.credentials()!!.privateJwk).isNotEqualTo(firstKey)
        assertThat(connection.status().lastError).isNull()
    }

    @Test
    fun `registering again while pending needs a confirmation`() {
        connection.checkNow()
        val pending = connection.status().state as SupportConnectionState.Pending

        val overview = withMediator { GetSupportConnectionOverview().query() }
        assertThat(overview.canRegisterAgain).isTrue()
        assertThat(overview.registerAgainNeedsConfirm).isTrue()
        assertThatThrownBy { withMediator { RegisterSupportConnectionAgain(confirmed = false).execute() } }
            .isInstanceOf(SupportRegistrationNotAllowedException::class.java)
        assertThat((connection.status().state as SupportConnectionState.Pending).clientId).isEqualTo(pending.clientId)

        withMediator { RegisterSupportConnectionAgain(confirmed = true).execute() }

        assertThat((connection.status().state as SupportConnectionState.Pending).clientId).isNotEqualTo(pending.clientId)
    }

    @Test
    fun `a register refused while active leaves the stored contact as it was`() {
        connection.checkNow()
        support.activate()
        connection.checkNow()
        val before = store.contact()

        assertThatThrownBy { connection.registerNow(StoredSupportContact("Someone Else B.V.", "other@example.org")) }
            .isInstanceOf(SupportRegistrationNotAllowedException::class.java)

        assertThat(store.contact()).isEqualTo(before)
    }

    @Test
    fun `a failed check keeps a refusal another node stored while it ran`() {
        connection.checkNow()
        support.activate()
        connection.checkNow()
        val clientId = support.latestRegistration.clientId
        // While this check waits for the service, another node's token request is refused and stores it.
        support.route("GET", "/api/v1/connection") {
            store.saveStatus(StoredSupportStatus(clientId = clientId, state = StoredConnectionState.REFUSED, reason = "invalid_client"))
            FakeSupportResponse.json(503, """{"error":"temporarily_unavailable"}""")
        }
        try {
            connection.checkNow()
        } finally {
            support.clearRoutes()
        }

        val status = checkNotNull(store.status())
        assertThat(status.state).`as`("the refusal is kept").isEqualTo(StoredConnectionState.REFUSED)
        assertThat(status.lastError).isNotNull()
    }

    @Test
    fun `registering again is not offered while active`() {
        connection.checkNow()
        support.activate()
        connection.checkNow()

        assertThat(withMediator { GetSupportConnectionOverview().query() }.canRegisterAgain).isFalse()
        assertThatThrownBy { withMediator { RegisterSupportConnectionAgain(confirmed = true).execute() } }
            .isInstanceOf(SupportRegistrationNotAllowedException::class.java)
    }

    @Test
    fun `registering again re-reads the state under the lock and never forgets a registration just found activated`() {
        connection.checkNow()
        val pending = connection.status().state as SupportConnectionState.Pending
        // The admin confirmed while pending; meanwhile staff activated it and another node's check stored ACTIVE.
        support.activate()
        connection.checkNow()

        assertThatThrownBy { connection.registerAgain() }.isInstanceOf(SupportRegistrationNotAllowedException::class.java)

        val state = connection.status().state as SupportConnectionState.Active
        assertThat(state.clientId).isEqualTo(pending.clientId)
        assertThat(store.credentials()!!.clientId).isEqualTo(pending.clientId)
    }

    @Test
    fun `a stored registration that cannot be decrypted is forgotten and the installation registers again`() {
        connection.checkNow()
        val first = connection.status().state as SupportConnectionState.Pending
        // No command writes an undecryptable row: this is what a lost or rotated-away encryption key leaves.
        jdbi.useHandle<Exception> {
            it.createUpdate("UPDATE support_connections SET credentials = :c WHERE id = :id")
                .bind("c", "enc:v1:a-key-that-is-gone:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").bind("id", store.connectionId).execute()
        }
        connection.invalidate()

        connection.checkNow()

        val again = connection.status().state as SupportConnectionState.Pending
        assertThat(again.clientId).isNotEqualTo(first.clientId)
        assertThat(store.credentials()!!.clientId).isEqualTo(again.clientId)
    }

    @Test
    fun `a token asked for with an undecryptable registration is unavailable, and backs off`() {
        connection.checkNow()
        support.activate()
        connection.checkNow()
        // No command writes an undecryptable row: this is what a lost or rotated-away encryption key leaves.
        jdbi.useHandle<Exception> {
            it.createUpdate("UPDATE support_connections SET credentials = :c WHERE id = :id")
                .bind("c", "enc:v1:a-key-that-is-gone:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").bind("id", store.connectionId).execute()
        }
        connection.invalidate()
        connection.resetBackoff()
        // The token from the check has expired, so the next call asks for one.
        testClock.advanceBy(Duration.ofSeconds(support.tokenLifetimeSeconds))

        assertThatThrownBy { connection.accessToken() }.isInstanceOf(SupportUnavailableException::class.java)
        assertThatThrownBy { connection.accessToken() }.`as`("backed off: not decrypted again")
            .isInstanceOf(SupportUnavailableException::class.java).hasMessageContaining("Not contacting the support service until")
    }

    @Test
    fun `a failed check does not hide scopes a newer token on this node already carries`() {
        connection.checkNow()
        support.activate(scopes = setOf("requests"))
        connection.checkNow()
        support.activate(scopes = setOf("requests", "mirror"))
        testClock.advanceBy(Duration.ofSeconds(support.tokenLifetimeSeconds))
        connection.refreshTokenIfDue()
        assertThat(connection.hasScope(SupportScope.MIRROR)).`as`("the newer token's scopes").isTrue()

        support.metadataIssuer = "https://support.evil.example"
        connection.checkNow()

        assertThat(connection.status().lastError).contains("issuer mismatch")
        assertThat(connection.hasScope(SupportScope.MIRROR)).`as`("still the newer token's scopes").isTrue()
    }

    @Test
    fun `a grant or withdrawal found by the check replaces the token issued before it`() {
        connection.checkNow()
        val pendingToken = checkNotNull(connection.cachedAccessToken())
        assertThat(support.issuedTokens.getValue(pendingToken).second).isEmpty()

        support.activate(scopes = setOf("telemetry", "requests"))
        connection.checkNow()
        val grantedToken = checkNotNull(connection.cachedAccessToken())
        assertThat(grantedToken).isNotEqualTo(pendingToken)
        assertThat(support.issuedTokens.getValue(grantedToken).second).containsExactlyInAnyOrder("telemetry", "requests")

        support.activate(scopes = setOf("requests"))
        connection.checkNow()
        val withdrawnToken = checkNotNull(connection.cachedAccessToken())
        assertThat(support.issuedTokens.getValue(withdrawnToken).second).containsExactly("requests")
        assertThat(connection.hasScope(SupportScope.TELEMETRY)).isFalse()
    }

    @Test
    fun `while a service is paused the token without it fits, and no tick asks for another`() {
        connection.checkNow()
        support.activate(scopes = setOf("telemetry", "mirror"))
        support.pause(setOf("mirror"), "Incident")
        connection.checkNow()
        val token = checkNotNull(connection.cachedAccessToken())
        assertThat(support.issuedTokens.getValue(token).second).containsExactly("telemetry")
        val tokenRequests = support.requests.count { it.method == "POST" && it.path == "/oauth2/token" }

        // Older than a refit, younger than its refresh margin: a fitting token is kept.
        testClock.advanceBy(Duration.ofMinutes(2))
        repeat(3) { connection.refreshTokenIfDue() }

        assertThat(connection.cachedAccessToken()).isEqualTo(token)
        assertThat(support.requests.count { it.method == "POST" && it.path == "/oauth2/token" }).`as`("no new token while paused").isEqualTo(tokenRequests)
    }

    @Test
    fun `a newer token's scopes show on this node before the next check`() {
        connection.checkNow()
        assertThat(connection.status().state).isInstanceOf(SupportConnectionState.Pending::class.java)

        support.activate(scopes = setOf("mirror"))
        // The token from the check is still cached; let it age past its refresh margin.
        testClock.advanceBy(Duration.ofSeconds(support.tokenLifetimeSeconds))
        connection.refreshTokenIfDue()

        val state = connection.status().state as SupportConnectionState.Active
        assertThat(state.scopes).containsExactly(SupportScope.MIRROR)
        assertThat(connection.hasScope(SupportScope.MIRROR)).isTrue()
    }

    @Test
    fun `an unreachable support service is recorded as the last error, and the state is kept`() {
        connection.checkNow()
        val pending = connection.status().state as SupportConnectionState.Pending

        support.tokenResponse = { FakeSupportResponse.json(503, """{"error":"temporarily_unavailable"}""") }
        testClock.advanceBy(Duration.ofSeconds(support.tokenLifetimeSeconds))
        connection.checkNow()

        val status = connection.status()
        assertThat(status.state).isEqualTo(pending)
        assertThat(status.lastError).contains("503")
        assertThat(connection.lastContactFailed()).isTrue()
        assertThatThrownBy { connection.accessToken() }.matches { it.isSupportUnavailable() }

        support.tokenResponse = null
        connection.checkNow()
        assertThat(connection.status().lastError).isNull()
    }

    @Test
    fun `metadata that names another issuer is refused and nothing is registered`() {
        support.metadataIssuer = "https://support.evil.example"
        val before = support.registrationRequests.size

        connection.checkNow()

        assertThat(connection.status().state).isEqualTo(SupportConnectionState.Unregistered)
        assertThat(connection.status().lastError).contains("issuer mismatch")
        assertThat(support.registrationRequests).hasSize(before)
    }

    @Test
    fun `a rate-limited registration is retried at the next check`() {
        support.registrationResponse = { FakeSupportResponse.json(429, """{"error":"slow_down"}""") }

        connection.checkNow()
        assertThat(connection.status().state).isEqualTo(SupportConnectionState.Unregistered)
        assertThat(connection.status().lastError).contains("429")

        support.registrationResponse = null
        connection.checkNow()
        assertThat(connection.status().state).isInstanceOf(SupportConnectionState.Pending::class.java)
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
            // These tests let the check register; without it, an installation administrator registers.
            registry.add("epistola.support.auto-register") { "true" }
            registry.add("epistola.support.endpoint") { support.baseUrl }
            registry.add("epistola.support.allow-http") { "true" }
            registry.add("epistola.installation.company-name") { "Gemeente Voorbeeld" }
            registry.add("epistola.installation.environment") { "test" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = support.close()
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.cluster.ClusterNodeRegistry
import app.epistola.suite.testing.FakeSupportFault
import app.epistola.suite.testing.FakeSupportResponse
import app.epistola.suite.testing.FakeSupportServer
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.dueScheduledTask
import app.epistola.suite.time.EpistolaClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.system.measureTimeMillis

/**
 * The support connection when the support service is down, slow or misbehaving (the fault modes of
 * [FakeSupportServer]): no suite thread waits long, nothing local is lost, retries back off, and
 * everything works again once the fault clears. `read-timeout` is [DEADLINE_MS] here, the deadline for
 * a whole call.
 */
class SupportConnectionResilienceIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var connection: DefaultSupportConnection

    @Autowired
    private lateinit var store: SupportConnectionStore

    @Autowired
    private lateinit var checkScheduler: SupportConnectionCheckScheduler

    @Autowired
    private lateinit var tokenScheduler: SupportTokenRefreshScheduler

    @Autowired
    private lateinit var work: SupportBackgroundWork

    @Autowired
    private lateinit var nodeRegistry: ClusterNodeRegistry

    @BeforeEach
    fun forgetRegistration() {
        support.clearFaults()
        support.start()
        support.tokenResponse = null
        support.registrationResponse = null
        support.checksAssertionTimes = false
        store.forget()
        connection.invalidate()
        connection.resetBackoff()
    }

    @AfterEach
    fun healthy() {
        support.clearFaults()
        support.start()
        support.tokenResponse = null
        support.checksAssertionTimes = false
        work.awaitIdle()
    }

    private fun activate(): String {
        connection.checkNow()
        val clientId = (connection.status().state as SupportConnectionState.Pending).clientId
        support.activate()
        connection.checkNow()
        assertThat(connection.status().state).isInstanceOf(SupportConnectionState.Active::class.java)
        return clientId
    }

    private fun tokenRequests() = support.requests.count { it.path == "/oauth2/token" }

    @Test
    fun `a service that trickles its answer is cut off at the deadline for the whole call`() {
        support.fault(FakeSupportFault.SlowDrip(intervalMillis = 300, totalMillis = HANG_MS, body = """{"issuer":"${"x".repeat(40)}"}"""), "GET", "/.well-known/")

        val took = measureTimeMillis { connection.checkNow() }

        assertThat(took).`as`("one deadline per call, not per read").isLessThan(CUT_OFF_MS)
        assertThat(connection.status().lastError).isNotNull()
    }

    @Test
    fun `a service that never answers is named as too slow, not as a cancelled request`() {
        support.fault(FakeSupportFault.Hang(maxMillis = HANG_MS), "GET", "/.well-known/")

        connection.checkNow()

        assertThat(connection.status().lastError)
            .contains("the support service did not answer in time").doesNotContain("Request cancelled")
    }

    @Test
    fun `a service nobody listens on is named as unreachable, not as an HTTP client's I-O error`() {
        support.stop()

        connection.checkNow()

        assertThat(connection.status().lastError)
            .contains("the support service could not be reached (connection refused)")
            .doesNotContain("I/O error").doesNotContain(": null")
    }

    @Test
    fun `an oversized answer is not read whole`() {
        support.bytesWritten.set(0)
        support.fault(FakeSupportFault.Oversized(64L * 1024 * 1024), "GET", "/.well-known/")

        connection.checkNow()

        assertThat(connection.status().lastError).isNotNull()
        assertThat(support.bytesWritten.get()).`as`("the suite stopped reading at its cap").isLessThan(12L * 1024 * 1024)
    }

    @Test
    fun `an upload the service never reads is given up at the deadline`() {
        activate()
        support.fault(FakeSupportFault.StallBeforeReadingBody(maxMillis = 60_000), "PUT", "/api/v1/mirror/releases/")

        assertTimeoutPreemptively(Duration.ofSeconds(20)) {
            assertThatThrownBy {
                connection.restClient().put().uri("/mirror/releases/{hash}", "v1:00").body(ByteArray(2 * 1024 * 1024)).retrieve().toBodilessEntity()
            }.matches { it.isSupportUnavailable() }
        }
    }

    @Test
    fun `an HTML 401 from a proxy keeps a pending registration`() {
        connection.checkNow()
        val pending = connection.status().state as SupportConnectionState.Pending
        support.expireTokens()
        support.tokenResponse = { FakeSupportResponse(401, "<html>Unauthorized</html>".toByteArray(), "text/html") }

        connection.checkNow()

        assertThat(store.credentials()!!.clientId).isEqualTo(pending.clientId)
        assertThat((connection.status().state as SupportConnectionState.Pending).clientId).isEqualTo(pending.clientId)
        assertThat(connection.status().lastError).isNotNull()
    }

    @Test
    fun `an HTML 401 from a proxy keeps an active registration`() {
        val clientId = activate()
        support.expireTokens()
        support.tokenResponse = { FakeSupportResponse(401, "<html>Unauthorized</html>".toByteArray(), "text/html") }

        connection.checkNow()

        assertThat((connection.status().state as SupportConnectionState.Active).clientId).isEqualTo(clientId)
        assertThat(connection.status().lastError).isNotNull()
    }

    /**
     * Plays a node whose clock is [offset] off the support service's. The fake checks assertion times
     * against the real clock, the one its `Date` header states, so this node's clock is set from the
     * real one: the only way to put the two a known distance apart.
     */
    private fun nodeClockOff(offset: Duration) {
        support.checksAssertionTimes = true
        testClock.set(java.time.Instant.now().plus(offset))
    }

    @Test
    fun `an active registration is not refused for a node's clock being off, and the error names the difference`() {
        activate()
        nodeClockOff(Duration.ofMinutes(10))

        connection.checkNow()

        assertThat(store.status()!!.state).`as`("the service refused the clock, not the registration").isEqualTo(StoredConnectionState.ACTIVE)
        assertThat(connection.status().state).isInstanceOf(SupportConnectionState.Active::class.java)
        assertThat(connection.status().lastError).contains("clock").contains("about 10 minutes ahead")
        assertThatThrownBy { connection.accessToken() }.isInstanceOf(SupportUnavailableException::class.java)
        // Only this node's problem: the installation-wide last error stays clear, so the other nodes keep sending.
        assertThat(store.status()!!.lastError).`as`("not the installation's last error").isNull()
        connection.resetBackoff() // forget this node's own failure: what any other node sees
        assertThat(connection.lastContactFailed()).`as`("other nodes' gates stay open").isFalse()
        assertThat(connection.status().nodeWarning).`as`("named on every node's page").contains("about 10 minutes ahead")

        // Once the clock is right, it carries on.
        nodeClockOff(Duration.ZERO)
        connection.resetBackoff()
        connection.checkNow()
        assertThat(connection.status().lastError).isNull()
        assertThat(connection.status().nodeWarning).isNull()
        assertThat(connection.hasScope(SupportScope.REQUESTS)).isTrue()
    }

    /**
     * The scheduled check, like the feedback sweep and poll and the mirror round, runs on one node
     * picked by rendezvous hashing over the nodes that advertise the capability it requires. A node
     * whose clock is off cannot talk to the service; were it the owner, that work would stop for the
     * whole installation. So it stops advertising that capability, and the work moves to the others.
     */
    @Test
    fun `a node whose clock is off hands the scheduled check to the other nodes, and takes it back once its clock is right`() {
        activate()
        val required = checkScheduler.supportConnectionCheckTaskDefinition().requiredCapability
        assertThat(nodeRegistry.heartbeat().capabilities).`as`("a node whose clock is fine may own it").contains(required)
        nodeClockOff(Duration.ofMinutes(10))
        support.expireTokens()

        connection.refreshTokenIfDue()

        assertThat(nodeRegistry.heartbeat().capabilities).`as`("not owned by a node that cannot send").doesNotContain(required)

        nodeClockOff(Duration.ZERO)
        connection.checkNow()
        assertThat(connection.status().lastError).isNull()
        assertThat(nodeRegistry.heartbeat().capabilities).contains(required)
    }

    /**
     * Only the node whose clock is off meets the refusal, mostly at its token refresh: the scheduled
     * check usually runs elsewhere. Its warning must reach every node's page all the same.
     */
    @Test
    fun `a node whose clock is off that does not run the check still names itself on every node's page`() {
        activate()
        nodeClockOff(Duration.ofMinutes(10))
        support.expireTokens()

        connection.refreshTokenIfDue()

        connection.resetBackoff() // forget this node's own failure: what any other node sees
        connection.invalidate()
        assertThat(connection.status().nodeWarning).`as`("named on every node's page").contains("about 10 minutes ahead")
        assertThat(connection.status().lastError).`as`("not the installation's last error").isNull()

        // Set right, the node clears its warning itself at its next token.
        nodeClockOff(Duration.ZERO)
        connection.checkNow()
        assertThat(connection.status().nodeWarning).isNull()
    }

    /** A check that registered on a node whose clock is off keeps the registration it stored. */
    @Test
    fun `registering on a node whose clock is off keeps the new registration's status`() {
        nodeClockOff(Duration.ofMinutes(10))

        connection.checkNow()

        val credentials = store.credentials()!!
        val stored = store.status()!!
        assertThat(stored.clientId).`as`("the registration stays the status's").isNotNull().isEqualTo(credentials.clientId)
        assertThat(stored.state).isEqualTo(StoredConnectionState.PENDING)
        assertThat(stored.claimCodeExpiresAt).`as`("the page can say when the claim code expires").isNotNull()
        assertThat(connection.status().nodeWarning).contains("about 10 minutes ahead")

        nodeClockOff(Duration.ZERO)
        connection.resetBackoff()
        connection.checkNow()
        assertThat(connection.status().nodeWarning).isNull()
    }

    @Test
    fun `a revoked registration is refused also on a node whose clock is off`() {
        activate()
        support.revoke()
        nodeClockOff(Duration.ofMinutes(10))

        connection.checkNow()

        assertThat(connection.status().state).isInstanceOf(SupportConnectionState.Refused::class.java)
        assertThat(store.status()!!.state).isEqualTo(StoredConnectionState.REFUSED)
    }

    @Test
    fun `a pending registration is not forgotten when only a node's clock says its claim code expired`() {
        connection.checkNow()
        val pending = connection.status().state as SupportConnectionState.Pending
        val registrations = support.registrations.size
        // Eight days ahead: past the claim code's expiry by this node's clock, not by the service's.
        nodeClockOff(Duration.ofDays(8))
        assertThat(EpistolaClock.instant()).isAfter(pending.claimCodeExpiresAt)

        connection.checkNow()

        assertThat(store.credentials()!!.clientId).`as`("the key is kept").isEqualTo(pending.clientId)
        assertThat(support.registrations).`as`("no new registration").hasSize(registrations)
        assertThat(connection.status().lastError).contains("about 8 days ahead")
    }

    @Test
    fun `invalid_client long before the claim code expires does not register again on every check`() {
        connection.checkNow()
        val pending = connection.status().state as SupportConnectionState.Pending
        val registrations = support.registrations.size
        support.tokenResponse = { FakeSupportResponse.json(401, """{"error":"invalid_client","error_description":"assertion issued in the future"}""") }

        repeat(3) {
            support.expireTokens()
            connection.checkNow()
        }

        assertThat(support.registrations).hasSize(registrations)
        assertThat(store.credentials()!!.clientId).isEqualTo(pending.clientId)
        assertThat(connection.status().lastError).contains("invalid_client")
    }

    @Test
    fun `a failed token refresh backs off instead of retrying every round, and recovers once the service does`() {
        activate()
        support.expireTokens()
        support.tokenResponse = { FakeSupportResponse.json(503, "{}") }
        testClock.advanceBy(Duration.ofSeconds(support.tokenLifetimeSeconds))

        connection.refreshTokenIfDue()
        val afterFirst = tokenRequests()
        connection.refreshTokenIfDue()
        connection.refreshTokenIfDue()
        assertThat(tokenRequests()).`as`("within the backoff").isEqualTo(afterFirst)
        assertThatThrownBy { connection.accessToken() }.isInstanceOf(SupportUnavailableException::class.java)
        assertThat(tokenRequests()).`as`("accessToken fails fast within the backoff").isEqualTo(afterFirst)

        support.tokenResponse = null
        testClock.advanceBy(Duration.ofMinutes(6))
        connection.refreshTokenIfDue()
        assertThat(connection.cachedAccessToken()).isNotNull()
    }

    @Test
    fun `a token the service stops accepting is replaced as soon as a data call reports it, not at the next check`() {
        activate()
        val first = checkNotNull(connection.cachedAccessToken())
        // The support service changed its signing key: every token it issued is refused from now on.
        support.expireTokens()
        val before = tokenRequests()

        connection.tokenRejected(first)
        connection.awaitTokenRenewal()

        val renewed = connection.cachedAccessToken()
        assertThat(renewed).isNotNull().isNotEqualTo(first)
        assertThat(support.issuedTokens).containsKey(renewed)
        assertThat(tokenRequests()).`as`("one new token").isEqualTo(before + 1)
        assertThat(connection.status().state).`as`("a 401 never marks the registration refused").isInstanceOf(SupportConnectionState.Active::class.java)
    }

    @Test
    fun `a service that refuses every token is asked for a new one at most once per back-off`() {
        activate()
        support.expireTokens()
        connection.tokenRejected(checkNotNull(connection.cachedAccessToken()))
        connection.awaitTokenRenewal()
        val second = checkNotNull(connection.cachedAccessToken())
        // Refused again: the service accepts no token at all.
        support.expireTokens()
        val before = tokenRequests()

        repeat(10) {
            connection.tokenRejected(second)
            connection.awaitTokenRenewal()
        }
        assertThat(tokenRequests()).`as`("within the back-off").isEqualTo(before)

        testClock.advanceBy(Duration.ofSeconds(31))
        connection.tokenRejected(second)
        connection.awaitTokenRenewal()
        assertThat(tokenRequests()).`as`("once the back-off is over").isEqualTo(before + 1)
        assertThat(connection.status().state).isInstanceOf(SupportConnectionState.Active::class.java)
    }

    /** A telemetry export goes around [SupportConnection.restClient]: it reports a token accepted itself. */
    @Test
    fun `a token reported accepted ends the 401 back-off, so the next 401 replaces it at once`() {
        activate()
        support.expireTokens()
        connection.tokenRejected(checkNotNull(connection.cachedAccessToken()))
        connection.awaitTokenRenewal()
        val second = checkNotNull(connection.cachedAccessToken())
        connection.tokenAccepted(second)
        // The service restarts again, well within the 30-second back-off.
        support.expireTokens()
        val before = tokenRequests()

        connection.tokenRejected(second)
        connection.awaitTokenRenewal()

        assertThat(tokenRequests()).`as`("replaced at once").isEqualTo(before + 1)
        assertThat(connection.cachedAccessToken()).isNotNull().isNotEqualTo(second)
    }

    @Test
    fun `data calls answered 401 every time do not fetch a token per call`() {
        activate()
        support.route("GET", "/api/v1/always-unauthorized") { FakeSupportResponse.invalidToken() }
        val before = tokenRequests()

        repeat(5) {
            assertThatThrownBy { connection.restClient().get().uri("/always-unauthorized").retrieve().toBodilessEntity() }
        }

        assertThat(tokenRequests() - before).`as`("one new token for the first 401, none for the rest within the back-off").isEqualTo(1)
        assertThat(connection.status().state).isInstanceOf(SupportConnectionState.Active::class.java)
    }

    @Test
    fun `a 401 after a signing-key change is retried with a new token every time the new one works`() {
        activate()
        repeat(3) {
            support.expireTokens()
            assertThat(connection.restClient().get().uri("/connection").retrieve().body(String::class.java)).contains("clientId")
        }
    }

    @Test
    fun `accessToken does not queue behind another thread's hanging token request`() {
        activate()
        support.expireTokens()
        testClock.advanceBy(Duration.ofSeconds(support.tokenLifetimeSeconds))
        support.tokenResponse = {
            Thread.sleep(3_000)
            FakeSupportResponse.json(503, "{}")
        }
        val clock = EpistolaClock.capture()
        val background = CompletableFuture.runAsync { EpistolaClock.withClock(clock) { connection.refreshTokenIfDue() } }
        Thread.sleep(100)

        val took = measureTimeMillis {
            assertThatThrownBy { connection.accessToken() }.isInstanceOf(SupportUnavailableException::class.java)
        }

        assertThat(took).isLessThan(500)
        background.join()
    }

    @Test
    fun `the token refresh and check tasks hand their network calls off the scheduler thread`() {
        activate()
        support.expireTokens()
        testClock.advanceBy(Duration.ofSeconds(support.tokenLifetimeSeconds))
        support.fault(FakeSupportFault.Hang(maxMillis = HANG_MS))

        val took = measureTimeMillis {
            tokenScheduler.handle(dueScheduledTask(tokenScheduler.supportTokenRefreshTaskDefinition()))
            checkScheduler.handle(dueScheduledTask(checkScheduler.supportConnectionCheckTaskDefinition()))
        }

        assertThat(took).isLessThan(500)
        assertThat(work.awaitIdle(Duration.ofSeconds(30))).isTrue()
    }

    @Test
    fun `a service that refuses connections is recorded, the state kept, and the next check recovers`() {
        val clientId = activate()
        support.stop()

        val took = measureTimeMillis { connection.checkNow() }

        assertThat(took).isLessThan(3_000)
        assertThat((connection.status().state as SupportConnectionState.Active).clientId).isEqualTo(clientId)
        assertThat(connection.status().lastError)
            .contains("the support service could not be reached (connection refused)")
            .doesNotContain("I/O error").doesNotContain(": null")

        support.start()
        connection.checkNow()
        assertThat(connection.status().lastError).isNull()
    }

    @ParameterizedTest
    @MethodSource("answers")
    fun `a misbehaving connection endpoint is recorded quickly and the state kept`(fault: FakeSupportFault) {
        val clientId = activate()
        support.fault(fault, "GET", "/api/v1/connection")

        val took = measureTimeMillis { connection.checkNow() }

        assertThat(took).`as`("cut off at the deadline at the latest").isLessThan(CUT_OFF_MS)
        assertThat((connection.status().state as SupportConnectionState.Active).clientId).isEqualTo(clientId)
        assertThat(connection.status().lastError).isNotNull()

        support.clearFaults()
        connection.checkNow()
        assertThat(connection.status().lastError).isNull()
    }

    @Test
    fun `a registration answer with a null claim code expiry is accepted`() {
        support.registrationResponse = { request ->
            val body = request.json()
            FakeSupportResponse.json(
                201,
                """{"client_id":"sc_nullexp","client_id_issued_at":1,"jwks":${body.path("jwks")},"epistola_claim_code":"ABCD-EFGH","epistola_claim_code_expires_at":null,"epistola_status":"PENDING"}""",
            )
        }

        connection.checkNow()

        val pending = connection.status().state as SupportConnectionState.Pending
        assertThat(pending.clientId).isEqualTo("sc_nullexp")
        assertThat(pending.claimCode).isEqualTo("ABCD-EFGH")
    }

    companion object {
        /**
         * The deadline for a whole call (`read-timeout`). Real time: it cuts off a hanging or trickling
         * service, and an ordinary call has to finish within it too. At one second, a runner that
         * stalled for a second failed the registration in `activate()` before any fault was set
         * (CI, 11 November); five leaves an ordinary call ample room.
         */
        private const val DEADLINE_MS = 5_000L

        /** How long a hanging or trickling service holds a call: far past [DEADLINE_MS]. */
        private const val HANG_MS = 60_000L

        /** A call cut off at [DEADLINE_MS] returns well within this; one that waited for [HANG_MS] does not. */
        private const val CUT_OFF_MS = 15_000L

        private val support = FakeSupportServer()

        @JvmStatic
        fun answers() = listOf(
            FakeSupportFault.serverError(),
            FakeSupportFault.badGatewayHtml(),
            FakeSupportFault.unavailable(),
            FakeSupportFault.tooManyRequests(),
            FakeSupportFault.malformedJson(),
            FakeSupportFault.TruncatedBody(),
            FakeSupportFault.Hang(maxMillis = HANG_MS),
        )

        @JvmStatic
        @DynamicPropertySource
        fun supportService(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.enabled") { "true" }
            registry.add("epistola.support.privacy.tenant-identity") { "named" }
            registry.add("epistola.support.services") { "telemetry,requests,mirror" }
            registry.add("epistola.support.auto-register") { "true" }
            registry.add("epistola.support.endpoint") { support.baseUrl }
            registry.add("epistola.support.allow-http") { "true" }
            registry.add("epistola.support.connect-timeout") { "1s" }
            registry.add("epistola.support.read-timeout") { "${DEADLINE_MS}ms" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = support.close()
    }
}

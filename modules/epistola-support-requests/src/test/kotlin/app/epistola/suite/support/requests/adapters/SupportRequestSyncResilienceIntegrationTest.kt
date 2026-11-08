// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.adapters

import app.epistola.suite.common.ids.SupportRequestCommentId
import app.epistola.suite.common.ids.SupportRequestCommentKey
import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.common.ids.SupportRequestKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.connection.DefaultSupportConnection
import app.epistola.suite.support.connection.SupportBackgroundWork
import app.epistola.suite.support.connection.SupportConnectionState
import app.epistola.suite.support.connection.lastContactFailed
import app.epistola.suite.support.requests.SupportRequest
import app.epistola.suite.support.requests.SupportRequestCategory
import app.epistola.suite.support.requests.SupportRequestPriority
import app.epistola.suite.support.requests.SupportRequestStatus
import app.epistola.suite.support.requests.SyncStatus
import app.epistola.suite.support.requests.commands.AddSupportRequestComment
import app.epistola.suite.support.requests.commands.CreateSupportRequest
import app.epistola.suite.support.requests.commands.UpdateSupportRequestStatus
import app.epistola.suite.support.requests.consent.GrantSupportRequestSyncConsent
import app.epistola.suite.support.requests.consent.WithdrawSupportRequestSyncConsent
import app.epistola.suite.support.requests.queries.GetSupportRequest
import app.epistola.suite.support.requests.sync.SupportRequestSyncScheduler
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.FakeSupportFault
import app.epistola.suite.testing.FakeSupportRequest
import app.epistola.suite.testing.FakeSupportResponse
import app.epistola.suite.testing.FakeSupportServer
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

/**
 * Sending feedback to Epistola support when the service is down, slow or misbehaving: no user
 * action waits on it, nothing local is lost, everything goes once the fault clears, and a round
 * neither storms nor runs on. The real REST adapter against [FakeSupportServer]; `read-timeout` is
 * one second, a sweep round's budget two.
 */
class SupportRequestSyncResilienceIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var connection: DefaultSupportConnection

    @Autowired
    private lateinit var sweep: SupportRequestSyncScheduler

    @Autowired
    private lateinit var work: SupportBackgroundWork

    private val author = UserKey.of("00000000-0000-0000-0000-feedbac00313")

    private val tenants = mutableListOf<Tenant>()

    @BeforeEach
    fun connect() {
        support.clearFaults()
        support.start()
        ensureUser(author, "support-request-resilience-author", "support-request-resilience@epistola.test", "Feedback Resilience Author")
        if (connection.status().state !is SupportConnectionState.Pending && connection.status().state !is SupportConnectionState.Active) connection.checkNow()
        support.activate(scopes = setOf("requests"))
        connection.checkNow()
        assertThat(connection.hasScope(app.epistola.suite.support.connection.SupportScope.REQUESTS)).isTrue()
        support.route("PUT", "/api/v2/requests/") { r -> scoped(r) { FakeSupportResponse.json(201, """{"requestId":"fb-${r.path.substringAfterLast('/')}","url":null}""") } }
        support.route("PUT", "/api/v2/requests/fb-") { r -> scoped(r) { FakeSupportResponse.json(201, """{"messageId":"c-${r.path.substringAfterLast('/')}"}""") } }
    }

    /** What a test leaves behind is not sent by the next: its tenants stop sharing. */
    @AfterEach
    fun withdraw() {
        support.clearFaults()
        support.start()
        work.awaitIdle()
        withMediator { tenants.forEach { WithdrawSupportRequestSyncConsent(it.id).execute() } }
        tenants.clear()
    }

    private fun scoped(request: FakeSupportRequest, answer: () -> FakeSupportResponse): FakeSupportResponse = when {
        request.clientId == null -> FakeSupportResponse.invalidToken()
        "requests" !in request.tokenScopes -> FakeSupportResponse.insufficientScope("requests")
        else -> answer()
    }

    private fun sharingTenant(name: String): Tenant = createTenant(name).also {
        tenants += it
        withMediator { GrantSupportRequestSyncConsent(it.id).execute() }
    }

    private fun create(tenant: Tenant, title: String = "Resilience"): SupportRequest = withMediator {
        CreateSupportRequest(
            id = SupportRequestId(SupportRequestKey.generate(), TenantId(tenant.id)),
            title = title,
            description = "desc",
            category = SupportRequestCategory.BUG,
            priority = SupportRequestPriority.MEDIUM,
            sourceUrl = null,
            consoleLogs = null,
            metadata = null,
            createdBy = author,
        ).execute()
    }

    private fun reload(supportRequest: SupportRequest): SupportRequest = withMediator { GetSupportRequest(SupportRequestId(supportRequest.id, TenantId(supportRequest.tenantKey))).query()!! }

    /** Feedback already at the service. */
    private fun synced(tenant: Tenant): SupportRequest {
        val supportRequest = create(tenant)
        work.awaitIdle()
        sweep.retryPendingSync()
        return reload(supportRequest).also { assertThat(it.syncStatus).isEqualTo(SyncStatus.SYNCED) }
    }

    private fun comment(supportRequest: SupportRequest, body: String) = withMediator {
        AddSupportRequestComment(SupportRequestCommentId(SupportRequestCommentKey.generate(), SupportRequestId(supportRequest.id, TenantId(supportRequest.tenantKey))), body, "Resilience", null).execute()
    }

    @Test
    fun `submitting support request and commenting never wait on a hanging service`() {
        val tenant = sharingTenant("Feedback Hang")
        val supportRequest = synced(tenant)
        support.fault(FakeSupportFault.Hang(maxMillis = 5_000), "PUT")

        val tookCreate = measureTimeMillis { create(tenant) }
        val tookComments = measureTimeMillis {
            comment(supportRequest, "first")
            comment(supportRequest, "second")
        }
        val tookStatus = measureTimeMillis { withMediator { UpdateSupportRequestStatus(SupportRequestId(supportRequest.id, TenantId(supportRequest.tenantKey)), SupportRequestStatus.IN_PROGRESS).execute() } }

        assertThat(tookCreate).`as`("submit").isLessThan(300)
        assertThat(tookComments).`as`("two comments").isLessThan(300)
        assertThat(tookStatus).`as`("status").isLessThan(300)
    }

    @Test
    fun `a status changed while the service is down reaches it once the service is back`() {
        val tenant = sharingTenant("Feedback Status Down")
        val supportRequest = synced(tenant)
        support.fault(FakeSupportFault.unavailable(), "PUT", "/api/v2/requests/fb-")

        withMediator { UpdateSupportRequestStatus(SupportRequestId(supportRequest.id, TenantId(supportRequest.tenantKey)), SupportRequestStatus.CLOSED).execute() }
        work.awaitIdle()
        support.clearFaults()
        connection.resetBackoff()
        connection.checkNow()
        sweep.retryPendingSync()

        val sent = statusMessages(supportRequest.externalRef).filter { support.faulted.none { f -> f === it } }
        assertThat(sent.map { it.json().path("status").stringValue() }).contains("CLOSED")
    }

    @Test
    fun `a status the service refuses backs off, doubling, instead of being tried every minute`() {
        val tenant = sharingTenant("Feedback Status Refused")
        val supportRequest = synced(tenant)
        // The question went with the request; every later message on it is the status.
        val statusPath = "/api/v2/requests/${supportRequest.externalRef}/messages/"
        support.fault(FakeSupportFault.Respond(FakeSupportResponse.json(404, """{"error":"not_found"}""")), "PUT", statusPath)

        // The change's own round is the first refusal: next try after 1 min, then 2, then 4.
        withMediator { UpdateSupportRequestStatus(SupportRequestId(supportRequest.id, TenantId(supportRequest.tenantKey)), SupportRequestStatus.CLOSED).execute() }
        work.awaitIdle()
        repeat(6) {
            testClock.advanceBy(Duration.ofSeconds(61))
            sweep.retryPendingSync()
        }

        // Minute by minute it would be 7 tries; doubling gives the first, at 1:01 and at 3:03.
        val tries = statusMessages(supportRequest.externalRef).size
        assertThat(tries).isEqualTo(3)
    }

    @Test
    fun `a status changed while the support request is being sent still reaches the service`() {
        val tenant = sharingTenant("Feedback Status Racing")
        val arrived = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        support.route("PUT", "/api/v2/requests/${tenant.id.value}/") { r ->
            arrived.countDown()
            proceed.await(5, TimeUnit.SECONDS)
            scoped(r) { FakeSupportResponse.json(201, """{"requestId":"fb-${r.path.substringAfterLast('/')}","url":null}""") }
        }

        // The change's own round sends the feedback, still OPEN; the user resolves it meanwhile.
        val supportRequest = create(tenant)
        assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue()
        withMediator { UpdateSupportRequestStatus(SupportRequestId(supportRequest.id, TenantId(supportRequest.tenantKey)), SupportRequestStatus.RESOLVED).execute() }
        proceed.countDown()
        work.awaitIdle()
        sweep.retryPendingSync()

        val sent = reload(supportRequest)
        assertThat(sent.syncStatus).isEqualTo(SyncStatus.SYNCED)
        val statuses = statusMessages(sent.externalRef)
        assertThat(statuses.map { it.json().path("status").stringValue() }).containsExactly("RESOLVED")
    }

    @Test
    fun `support request the service refused for a while is still sent once it takes it again`() {
        val tenant = sharingTenant("Feedback Refused While")
        support.fault(FakeSupportFault.Respond(FakeSupportResponse.json(404, """{"error":"not_found"}""")), "PUT", "/api/v2/requests/${tenant.id.value}/")
        val supportRequest = create(tenant)
        work.awaitIdle()

        repeat(6) {
            sweep.retryPendingSync()
            testClock.advanceBy(Duration.ofHours(7))
        }
        support.clearFaults()
        sweep.retryPendingSync()

        val now = reload(supportRequest)
        assertThat(now.syncStatus).isEqualTo(SyncStatus.SYNCED)
        assertThat(now.externalRef).isNotNull()
    }

    /** Creates feedback while the service is down, so it waits PENDING; then the service is back. */
    private fun <T> pendingWhileDown(block: () -> T): T {
        support.stop()
        val created = block()
        work.awaitIdle()
        support.start()
        connection.resetBackoff()
        connection.checkNow()
        return created
    }

    /**
     * Two nodes of one installation: a change on one starts a round there while the other runs its
     * own. Seen live with two nodes, every item went out twice, once from each node.
     */
    @Test
    fun `while one node sends support request, another node does not send it as well`() {
        val tenant = sharingTenant("Feedback Two Nodes")
        val sends = ConcurrentHashMap<String, AtomicInteger>()
        val arrived = CountDownLatch(1)
        val answer = CountDownLatch(1)
        support.route("PUT", "/api/v2/requests/${tenant.id.value}/") { r ->
            sends.computeIfAbsent(r.path) { AtomicInteger() }.incrementAndGet()
            arrived.countDown()
            // Held, but within the one-second read timeout, so no round reads it as an outage.
            answer.await(800, TimeUnit.MILLISECONDS)
            scoped(r) { FakeSupportResponse.json(201, """{"requestId":"fb-${r.path.substringAfterLast('/')}","url":null}""") }
        }

        // This node's round, started by the submission, is sending it now.
        val supportRequest = create(tenant, "Two nodes")
        assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue()
        // The other node's round, as a second JVM runs it: past this node's one-round-at-a-time guard.
        val otherNode = Thread.ofVirtual().start { withMediator { sweep.retryPendingSync() } }
        val path = "/api/v2/requests/${tenant.id.value}/${supportRequest.id}"
        val until = System.nanoTime() + Duration.ofMillis(400).toNanos()
        while ((sends[path]?.get() ?: 0) < 2 && System.nanoTime() < until) Thread.sleep(10)
        answer.countDown()
        otherNode.join(Duration.ofSeconds(10))
        work.awaitIdle()

        assertThat(sends[path]?.get()).`as`("sends of the one feedback item").isEqualTo(1)
        assertThat(reload(supportRequest).syncStatus).isEqualTo(SyncStatus.SYNCED)
    }

    @Test
    fun `a server error on one item does not hold up the items behind it`() {
        val tenant = sharingTenant("Feedback Head Of Line")
        val (a, b) = pendingWhileDown { create(tenant, "A") to create(tenant, "B") }
        support.fault(FakeSupportFault.serverError(), "PUT", "/api/v2/requests/${tenant.id.value}/${a.id.value}")

        sweep.retryPendingSync()

        assertThat(reload(b).syncStatus).isEqualTo(SyncStatus.SYNCED)
        assertThat(reload(a).syncStatus).isEqualTo(SyncStatus.PENDING)
        assertThat(reload(a).syncAttempts).`as`("a server error is not the item's fault").isZero()
    }

    @Test
    fun `a sweep against a slow service keeps to its round budget`() {
        val tenant = sharingTenant("Feedback Slow")
        pendingWhileDown { repeat(20) { create(tenant, "Slow $it") } }
        support.route("PUT", "/api/v2/requests/${tenant.id.value}/") { r ->
            Thread.sleep(700)
            scoped(r) { FakeSupportResponse.json(201, """{"requestId":"fb-${r.path.substringAfterLast('/')}","url":null}""") }
        }

        val took = measureTimeMillis { sweep.retryPendingSync() }

        assertThat(took).isLessThan(5_000)
    }

    @Test
    fun `an unreadable 200 from a proxy leaves support request pending without counting an attempt`() {
        val tenant = sharingTenant("Feedback Html")
        support.fault(FakeSupportFault.Respond(FakeSupportResponse(200, "<html>ok</html>".toByteArray(), "text/html")), "PUT", "/api/v2/requests/${tenant.id.value}/")
        val supportRequest = create(tenant)
        work.awaitIdle()

        sweep.retryPendingSync()

        val now = reload(supportRequest)
        assertThat(now.syncStatus).isEqualTo(SyncStatus.PENDING)
        assertThat(now.syncAttempts).isZero()
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["html", "truncated"])
    fun `an answer that cannot be read closes the gates until the next good check`(answer: String) {
        val tenant = sharingTenant("Feedback Unreadable $answer")
        val fault = if (answer == "html") {
            FakeSupportFault.Respond(FakeSupportResponse(200, "<html>captive portal</html>".toByteArray(), "text/html"))
        } else {
            FakeSupportFault.TruncatedBody(status = 201)
        }
        support.fault(fault, "PUT", "/api/v2/requests/${tenant.id.value}/")
        // A failure counts once it is newer than the last good check.
        testClock.advanceBy(Duration.ofSeconds(1))
        val supportRequest = create(tenant)
        work.awaitIdle()
        assertThat(connection.lastContactFailed()).`as`("noted as unavailable").isTrue()

        val calls = support.requests.count { it.method == "PUT" && it.path.startsWith("/api/v2/requests/${tenant.id.value}/") }
        sweep.retryPendingSync()
        assertThat(support.requests.count { it.method == "PUT" && it.path.startsWith("/api/v2/requests/${tenant.id.value}/") }).`as`("the gate is closed").isEqualTo(calls)

        support.clearFaults()
        connection.resetBackoff()
        connection.checkNow()
        sweep.retryPendingSync()
        assertThat(reload(supportRequest).syncStatus).isEqualTo(SyncStatus.SYNCED)
    }

    @Test
    fun `a service that refuses connections gets one try per round, and everything goes once it is back`() {
        val tenant = sharingTenant("Feedback Down")
        support.stop()
        val items = (1..3).map { create(tenant, "Down $it") }
        work.awaitIdle()
        sweep.retryPendingSync()
        assertThat(items.map { reload(it).syncStatus }).containsOnly(SyncStatus.PENDING)
        assertThat(items.map { reload(it).syncAttempts }).containsOnly(0)

        support.start()
        connection.resetBackoff()
        connection.checkNow()
        sweep.retryPendingSync()

        assertThat(items.map { reload(it).syncStatus }).containsOnly(SyncStatus.SYNCED)
    }

    companion object {
        private val support = FakeSupportServer()

        @JvmStatic
        @DynamicPropertySource
        fun supportService(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.enabled") { "true" }
            registry.add("epistola.support.privacy.tenant-identity") { "named" }
            registry.add("epistola.support.services") { "requests" }
            registry.add("epistola.support.auto-register") { "true" }
            registry.add("epistola.support.endpoint") { support.baseUrl }
            registry.add("epistola.support.allow-http") { "true" }
            registry.add("epistola.support.connect-timeout") { "1s" }
            registry.add("epistola.support.read-timeout") { "1s" }
            registry.add("epistola.support.requests.sync.enabled") { "true" }
            registry.add("epistola.support.requests.sync.retry-interval-ms") { "3600000" }
            registry.add("epistola.support.requests.sync.round-budget-ms") { "2000" }
            registry.add("epistola.support.requests.sync.polling.enabled") { "false" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = support.close()
    }

    /** The STATUS messages sent on the request the service knows as [externalRef]. */
    private fun statusMessages(externalRef: String?): List<FakeSupportRequest> = support.requests.filter {
        it.method == "PUT" && it.path.startsWith("/api/v2/requests/$externalRef/messages/") && it.json().path("kind").stringValue() == "STATUS"
    }
}

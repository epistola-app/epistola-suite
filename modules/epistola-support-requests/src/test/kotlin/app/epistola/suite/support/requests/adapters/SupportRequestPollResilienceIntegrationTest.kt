// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.adapters

import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.common.ids.SupportRequestKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.metadata.getAs
import app.epistola.suite.support.connection.DefaultSupportConnection
import app.epistola.suite.support.connection.EnsureConfiguredSupportConnection
import app.epistola.suite.support.connection.SupportBackgroundWork
import app.epistola.suite.support.connection.SupportConnectionState
import app.epistola.suite.support.connection.SupportScope
import app.epistola.suite.support.connection.lastContactFailed
import app.epistola.suite.support.requests.SupportRequest
import app.epistola.suite.support.requests.SupportRequestCategory
import app.epistola.suite.support.requests.SupportRequestPriority
import app.epistola.suite.support.requests.SyncStatus
import app.epistola.suite.support.requests.commands.CreateSupportRequest
import app.epistola.suite.support.requests.consent.GrantSupportRequestSyncConsent
import app.epistola.suite.support.requests.consent.WithdrawSupportRequestSyncConsent
import app.epistola.suite.support.requests.queries.GetSupportRequest
import app.epistola.suite.support.requests.queries.GetSupportRequestComments
import app.epistola.suite.support.requests.sync.SupportRequestPollScheduler
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.system.measureTimeMillis

/**
 * Reading Epistola's replies (the updates feed) while the support service is down, slow or
 * misbehaving: a poll keeps to its round budget, acknowledges only replies it applied, so none is
 * lost or applied twice, and the replies all arrive once the fault clears. The fake feed keeps the
 * acknowledged position, as the service does. The real REST adapter
 * and poll against [FakeSupportServer]; `read-timeout` is one second, a round's budget two, and the
 * fake feed answers two updates per page.
 */
class SupportRequestPollResilienceIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var connection: DefaultSupportConnection

    @Autowired
    private lateinit var sweep: SupportRequestSyncScheduler

    @Autowired
    private lateinit var poll: SupportRequestPollScheduler

    @Autowired
    private lateinit var work: SupportBackgroundWork

    private val author = UserKey.of("00000000-0000-0000-0000-feedbac00314")

    private val tenants = mutableListOf<Tenant>()

    /** Where this test's feed starts: its first reply is `base + 1`. */
    private val base = 0L

    private val feed = Feed()

    @BeforeEach
    fun connect() {
        support.clearFaults()
        support.start()
        ensureUser(author, "support-request-poll-author", "support-request-poll@epistola.test", "Feedback Poll Author")
        if (connection.status().state !is SupportConnectionState.Pending && connection.status().state !is SupportConnectionState.Active) connection.checkNow()
        support.activate(scopes = setOf("requests"))
        connection.resetBackoff()
        connection.checkNow()
        assertThat(connection.hasScope(SupportScope.REQUESTS)).isTrue()
        support.route("PUT", "/api/v2/requests/") { r ->
            scoped(r) {
                if (r.path.contains("/messages/")) {
                    FakeSupportResponse.json(201, """{"messageId":"m-${r.path.substringAfterLast('/')}"}""")
                } else {
                    FakeSupportResponse.json(201, """{"requestId":"fb-${r.path.substringAfterLast('/')}","url":null}""")
                }
            }
        }
        support.route("POST", FEED_PATH) { r -> scoped(r) { feed.page(r) } }
    }

    /** What a test leaves behind is not read by the next: its tenants stop sharing. */
    @AfterEach
    fun withdraw() {
        support.clearFaults()
        support.start()
        work.awaitIdle()
        withMediator { tenants.forEach { WithdrawSupportRequestSyncConsent(it.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() } }
        tenants.clear()
    }

    private fun scoped(request: FakeSupportRequest, answer: () -> FakeSupportResponse): FakeSupportResponse = when {
        request.clientId == null -> FakeSupportResponse.invalidToken()
        "requests" !in request.tokenScopes -> FakeSupportResponse.insufficientScope("requests")
        else -> answer()
    }

    /**
     * The service's updates feed: [replies] in seq order, at most [PAGE_SIZE] per page after what the
     * installation acknowledged ([acknowledged]). [beforePage] runs before a page is answered, with
     * the position it starts after; a fault it adds plays on the next request, so a test can break the
     * feed after the first page.
     */
    private inner class Feed {
        val replies = CopyOnWriteArrayList<Reply>()

        @Volatile var beforePage: (Long) -> Unit = {}

        @Volatile var pageDelayMillis = 0L

        /** The `acknowledgeUpTo` of every call answered, in order; null when it carried none. */
        val asked = CopyOnWriteArrayList<Long?>()

        /** What the installation acknowledged; never moves back. */
        @Volatile var acknowledged = base

        fun page(request: FakeSupportRequest): FakeSupportResponse {
            val ack = request.json().path("acknowledgeUpTo").takeIf { it.isNumber }?.asLong()
            asked += ack
            if (ack != null) acknowledged = maxOf(acknowledged, ack)
            val after = acknowledged
            beforePage(after)
            if (pageDelayMillis > 0) Thread.sleep(pageDelayMillis)
            return page(after)
        }

        /** The page after [after]; [padding] bytes more in a field the suite does not read. */
        fun page(after: Long, padding: Int = 0): FakeSupportResponse {
            val later = replies.filter { it.seq > after }
            val served = later.take(PAGE_SIZE)
            val entries = served.joinToString(",") { reply ->
                """{"sequence":${reply.seq},"requestId":"${reply.supportRequest.externalRef}","tenantKey":"${reply.supportRequest.tenantKey.value}","suiteRequestId":"${reply.supportRequest.id.value}","occurredAt":"2026-11-05T14:00:00Z","type":"MESSAGE","message":{"messageId":"${reply.commentId}","kind":"REPLY","authorName":"Epistola support","authorEmail":null,"body":"${reply.body}"}}"""
            }
            val last = served.lastOrNull()?.seq?.toString() ?: "null"
            val pad = if (padding > 0) ""","padding":"${"a".repeat(padding)}"""" else ""
            return FakeSupportResponse.json(200, """{"changes":[$entries],"hasMore":${later.size > served.size},"lastSequence":$last$pad}""")
        }
    }

    private data class Reply(val seq: Long, val supportRequest: SupportRequest, val commentId: String, val body: String)

    /** [count] replies from Epistola to [feedback], at the next seqs of this test. */
    private fun reply(supportRequest: SupportRequest, count: Int) {
        val first = base + feed.replies.size + 1
        (first until first + count).forEach { seq -> feed.replies += Reply(seq, supportRequest, "c-poll-$seq-${supportRequest.id.value}", "Reply $seq") }
    }

    private fun sharingTenant(name: String): Tenant = createTenant(name).also {
        tenants += it
        withMediator { GrantSupportRequestSyncConsent(it.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
    }

    /** Feedback already at the service, so replies to it can be applied. */
    private fun synced(tenant: Tenant): SupportRequest {
        val supportRequest = withMediator {
            CreateSupportRequest(
                id = SupportRequestId(SupportRequestKey.generate(), TenantId(tenant.id)),
                title = "Poll",
                description = "desc",
                category = SupportRequestCategory.QUESTION,
                priority = SupportRequestPriority.MEDIUM,
                sourceUrl = null,
                consoleLogs = null,
                metadata = null,
                createdBy = author,
            ).execute()
        }
        work.awaitIdle()
        sweep.retryPendingSync()
        return withMediator { GetSupportRequest(SupportRequestId(supportRequest.id, TenantId(supportRequest.tenantKey))).query()!! }
            .also { assertThat(it.syncStatus).isEqualTo(SyncStatus.SYNCED) }
    }

    /** The external comment ids applied to [feedback], in order, duplicates included. */
    private fun applied(supportRequest: SupportRequest): List<String?> = withMediator { GetSupportRequestComments(SupportRequestId(supportRequest.id, TenantId(supportRequest.tenantKey))).query() }
        .mapNotNull { it.externalCommentId }

    /** What the poll acknowledged at the (fake) service. */
    private fun cursor(): Long = feed.acknowledged

    private fun expected(): List<String> = feed.replies.map { it.commentId }

    /** Plays [fault] on the next feed request, which asks for the page after [nextAfter]. */
    private fun breakFeed(fault: String, times: Int = Int.MAX_VALUE, nextAfter: Long = base) {
        when (fault) {
            "refused" -> support.stop()

            "hang" -> support.fault(FakeSupportFault.Hang(maxMillis = 5_000), "POST", FEED_PATH, times)

            "trickle" -> support.fault(FakeSupportFault.SlowDrip(intervalMillis = 100, totalMillis = 5_000, body = """{"changes":[],"hasMore":false,"lastSequence":null}"""), "POST", FEED_PATH, times)

            "5xx" -> support.fault(FakeSupportFault.serverError(), "POST", FEED_PATH, times)

            "html-502" -> support.fault(FakeSupportFault.badGatewayHtml(), "POST", FEED_PATH, times)

            "malformed-json" -> support.fault(FakeSupportFault.Respond(FakeSupportResponse.json(200, """{"changes":[{"sequence":""")), "POST", FEED_PATH, times)

            "truncated" -> support.fault(FakeSupportFault.TruncatedBody(status = 200), "POST", FEED_PATH, times)

            // A real page of replies, over the suite's 4 MiB cap: applied only if the cap did not hold.
            "oversized" -> support.fault(FakeSupportFault.Respond(feed.page(nextAfter, padding = 5 * 1024 * 1024)), "POST", FEED_PATH, times)

            "insufficient-scope" -> support.fault(FakeSupportFault.Respond(FakeSupportResponse.insufficientScope("requests")), "POST", FEED_PATH, times)

            // Epistola revoked the registration: its token stops working and the token endpoint answers invalid_client.
            "invalid-client" -> support.revoke()

            else -> error("unknown fault $fault")
        }
    }

    /** The service is healthy again, and a check (the next one in production) has seen it. */
    private fun recover() {
        support.clearFaults()
        support.start()
        support.activate(scopes = setOf("requests"))
        connection.resetBackoff()
        connection.checkNow()
        assertThat(connection.hasScope(SupportScope.REQUESTS)).`as`("feedback usable again").isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["refused", "hang", "trickle", "5xx", "html-502", "malformed-json", "truncated", "oversized", "insufficient-scope", "invalid-client"])
    fun `a poll against a failing feed keeps to its budget, acknowledges nothing, and reads every reply once the fault clears`(fault: String) {
        val supportRequest = synced(sharingTenant("Feedback Poll $fault"))
        reply(supportRequest, 5)
        breakFeed(fault)
        val feedRequests = support.requests.count { it.path == FEED_PATH }
        // A failure is shown when it is later than the last check; the test clock stands still otherwise.
        testClock.advanceBy(java.time.Duration.ofSeconds(1))

        val took = measureTimeMillis { poll.pollForUpdates() }

        if (fault == "refused") {
            assertThat(connection.lastContactFailed()).`as`("the poll met the refused connection, not skipped").isTrue()
        } else {
            assertThat(support.requests.count { it.path == FEED_PATH }).`as`("the poll met the fault").isGreaterThan(feedRequests)
        }
        assertThat(took).`as`("within the round budget").isLessThan(ROUND_BUDGET_MS)
        assertThat(applied(supportRequest)).`as`("nothing applied").isEmpty()
        assertThat(cursor()).`as`("nothing acknowledged").isEqualTo(base)

        recover()
        poll.pollForUpdates()
        poll.pollForUpdates()

        assertThat(applied(supportRequest)).`as`("every reply, once").containsExactlyElementsOf(expected())
        assertThat(cursor()).isEqualTo(base + 5)
        assertThat(feed.asked.filterNotNull().last()).`as`("a round ends by acknowledging the last reply").isEqualTo(base + 5)
        assertThat(feed.asked.last()).`as`("a poll with nothing new acknowledges nothing").isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["refused", "hang", "trickle", "5xx", "html-502", "malformed-json", "truncated", "oversized", "insufficient-scope", "invalid-client"])
    fun `a feed that breaks after the first page keeps the replies of that page, and the next poll goes on from there`(fault: String) {
        val supportRequest = synced(sharingTenant("Feedback Poll Later $fault"))
        reply(supportRequest, 5)
        feed.beforePage = { after ->
            when {
                // The service stops between pages: the second page's request finds it gone.
                fault == "refused" -> if (after == base + PAGE_SIZE) support.stop()

                after == base -> breakFeed(fault, times = 1, nextAfter = base + PAGE_SIZE)
            }
        }

        val took = measureTimeMillis { poll.pollForUpdates() }

        assertThat(took).`as`("within the round budget").isLessThan(ROUND_BUDGET_MS)
        assertThat(applied(supportRequest)).`as`("the first page").containsExactlyElementsOf(expected().take(2))
        // The acknowledgement of the first page rides on the call that failed: it reached the service
        // only when the service went away after reading it.
        assertThat(cursor()).isEqualTo(if (fault == "refused") base + 2 else base)

        feed.beforePage = {}
        recover()
        poll.pollForUpdates()

        assertThat(applied(supportRequest)).`as`("every reply, once, also those the service sends again").containsExactlyElementsOf(expected())
        assertThat(cursor()).isEqualTo(base + 5)
    }

    @Test
    fun `a slow feed stops at the round budget, and the next poll reads on from what was acknowledged`() {
        val supportRequest = synced(sharingTenant("Feedback Poll Slow"))
        reply(supportRequest, 10)
        feed.pageDelayMillis = 700
        val before = feed.asked.size

        poll.pollForUpdates()

        // The budget is checked between pages: it ends a round at most one call past it. Counted in
        // calls, not in wall-clock time, which a busy machine stretches with the work between pages.
        val calls = feed.asked.size - before
        assertThat(calls).`as`("calls in the round").isBetween(1, (ROUND_BUDGET_MS / 700 + 2).toInt())
        val first = applied(supportRequest)
        assertThat(first).`as`("not all of it in one round").hasSizeLessThan(10).isNotEmpty
        // Every page but the last of the round is acknowledged; the last comes again and is not applied twice.
        assertThat(cursor()).isBetween(base + first.size - PAGE_SIZE, base + first.size)

        feed.pageDelayMillis = 0
        poll.pollForUpdates()

        assertThat(applied(supportRequest)).containsExactlyElementsOf(expected())
        assertThat(cursor()).isEqualTo(base + 10)
    }

    @Test
    fun `a token the service no longer accepts is replaced once, and the poll goes on`() {
        val supportRequest = synced(sharingTenant("Feedback Poll 401"))
        reply(supportRequest, 3)
        support.fault(FakeSupportFault.unauthorized(), "POST", FEED_PATH, times = 1)

        poll.pollForUpdates()

        assertThat(applied(supportRequest)).containsExactlyElementsOf(expected())
        assertThat(cursor()).isEqualTo(base + 3)
    }

    companion object {
        private const val FEED_PATH = "/api/v2/requests/changes"
        private const val PAGE_SIZE = 2
        private const val ROUND_BUDGET_MS = 2_000L
        private const val READ_TIMEOUT_MS = 1_000L

        private val support = FakeSupportServer()

        @JvmStatic
        @DynamicPropertySource
        fun supportService(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.enabled") { "true" }
            // These tests are about sending: Epistola handles new requests, as configured.
            registry.add("epistola.support.requests.handled-by") { "epistola" }
            registry.add("epistola.support.privacy.tenant-identity") { "named" }
            registry.add("epistola.support.services") { "requests" }
            registry.add("epistola.support.auto-register") { "true" }
            registry.add("epistola.support.endpoint") { support.baseUrl }
            registry.add("epistola.support.allow-http") { "true" }
            registry.add("epistola.support.connect-timeout") { "1s" }
            registry.add("epistola.support.read-timeout") { "${READ_TIMEOUT_MS}ms" }
            registry.add("epistola.support.requests.sync.enabled") { "true" }
            registry.add("epistola.support.requests.sync.retry-interval-ms") { "3600000" }
            registry.add("epistola.support.requests.sync.round-budget-ms") { "$ROUND_BUDGET_MS" }
            // The poll runs only when a test calls it.
            registry.add("epistola.support.requests.sync.polling.enabled") { "true" }
            registry.add("epistola.support.requests.sync.polling.interval-ms") { "3600000" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = support.close()
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.adapters

import app.epistola.suite.common.ids.SupportRequestCommentId
import app.epistola.suite.common.ids.SupportRequestCommentKey
import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.common.ids.SupportRequestKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.connection.SupportBackgroundWork
import app.epistola.suite.support.connection.SupportConnectionRegistry
import app.epistola.suite.support.connection.TokenSupportConnection
import app.epistola.suite.support.privacy.TenantReferences
import app.epistola.suite.support.requests.SupportRequestCategory
import app.epistola.suite.support.requests.SupportRequestPriority
import app.epistola.suite.support.requests.commands.AddSupportRequestComment
import app.epistola.suite.support.requests.commands.CreateSupportRequest
import app.epistola.suite.support.requests.consent.GrantSupportRequestSyncConsent
import app.epistola.suite.support.requests.consent.WithdrawSupportRequestSyncConsent
import app.epistola.suite.support.requests.desk.EscalateSupportRequest
import app.epistola.suite.support.requests.desk.ResetSupportRequestHandling
import app.epistola.suite.support.requests.desk.SetSupportRequestHandling
import app.epistola.suite.support.requests.queries.GetSupportRequestComments
import app.epistola.suite.support.requests.sync.SupportRequestPollScheduler
import app.epistola.suite.support.requests.sync.SupportRequestSyncScheduler
import app.epistola.suite.testing.FakeSupportRequest
import app.epistola.suite.testing.FakeSupportResponse
import app.epistola.suite.testing.FakeSupportServer
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Support requests over a partner connection (ADR 0053 step 3), signed in with a token the partner
 * issued, here a stand-in, with no connection to Epistola support: a request the partner handles goes
 * there, its replies come back as the partner's, and what may go is read per connection.
 */
@Isolated("Who handles new support requests applies to the whole installation")
class SupportRequestPartnerConnectionIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var registry: SupportConnectionRegistry

    @Autowired
    private lateinit var sweep: SupportRequestSyncScheduler

    @Autowired
    private lateinit var poll: SupportRequestPollScheduler

    @Autowired
    private lateinit var work: SupportBackgroundWork

    @Autowired
    private lateinit var references: TenantReferences

    @Autowired
    private lateinit var jdbi: Jdbi

    private val author = UserKey.of("00000000-0000-0000-0000-9a7700000001")

    /** The changes the partner hands out next, as its feed's JSON entries. */
    private val feed = CopyOnWriteArrayList<String>()

    private fun connection(): TokenSupportConnection = registry.tokenConnections().single { it.endpoint.toString() == partner.baseUrl.trimEnd('/') }

    private val partnerId: UUID get() = connection().id

    private fun scoped(request: FakeSupportRequest, answer: () -> FakeSupportResponse): FakeSupportResponse = when {
        request.clientId == null -> FakeSupportResponse.invalidToken()
        "requests" !in request.tokenScopes -> FakeSupportResponse.insufficientScope("requests")
        else -> answer()
    }

    @BeforeEach
    fun connect() {
        ensureUser(author, "partner-requests", "partner-requests@epistola.test", "Partner Requests")
        partner.issuePartnerToken(TOKEN, setOf("requests"))
        partner.route("PUT", "/api/v2/requests/") { r ->
            scoped(r) {
                if (r.path.contains("/messages/")) {
                    FakeSupportResponse.json(201, """{"messageId":"pm-${r.path.substringAfterLast('/')}"}""")
                } else {
                    FakeSupportResponse.json(201, """{"requestId":"pr-${r.path.substringAfterLast('/')}","url":null}""")
                }
            }
        }
        partner.route("POST", "/api/v2/requests/changes") { r ->
            scoped(r) {
                val entries = feed.toList().also { feed.clear() }
                val last = if (entries.isEmpty()) "null" else entries.size.toString()
                FakeSupportResponse.json(200, """{"changes":[${entries.joinToString(",")}],"hasMore":false,"lastSequence":$last}""")
            }
        }
        connection().checkNow()
        partner.requests.clear()
    }

    @AfterEach
    fun inbox() {
        work.awaitIdle()
        withMediator { ResetSupportRequestHandling().execute() }
    }

    private fun share(tenant: TenantKey) = withMediator { GrantSupportRequestSyncConsent(tenant, partnerId).execute() }

    private fun submit(tenant: TenantKey, title: String = "Blank preview"): SupportRequestId {
        val id = SupportRequestId(SupportRequestKey.generate(), TenantId(tenant))
        withMediator { CreateSupportRequest(id, title, "It stays blank", SupportRequestCategory.BUG, SupportRequestPriority.MEDIUM, null, null, null, author).execute() }
        work.awaitIdle()
        return id
    }

    private fun sentPaths(): List<String> = partner.requests.filter { it.method == "PUT" }.map { it.path }

    @Test
    fun `a request the partner handles goes to it with its token, the tenant named as the partner's privacy says`() {
        val tenant = createTenant("Partner Handles").id
        share(tenant)
        withMediator { SetSupportRequestHandling(partnerId).execute() }

        val id = submit(tenant)
        sweep.retryPendingSync()

        assertThat(sentPaths()).contains("/api/v2/requests/${references.reference(tenant)}/${id.key.value}")
        assertThat(partner.bearerTokensSeen.filter { it.first.startsWith("/api/v2/requests/") }.map { it.second }.distinct()).containsExactly(TOKEN)
        val handler = jdbi.withHandle<UUID?, Exception> { it.createQuery("SELECT handler_connection FROM support_requests WHERE id = :id").bind("id", id.key.value).mapTo(UUID::class.java).one() }
        assertThat(handler).isEqualTo(partnerId)
    }

    @Test
    fun `a reply from the partner comes back as the partner's`() {
        val tenant = createTenant("Partner Replies").id
        share(tenant)
        withMediator { SetSupportRequestHandling(partnerId).execute() }
        val id = submit(tenant)
        sweep.retryPendingSync()
        feed += """{"sequence":1,"requestId":"pr-${id.key.value}","tenantKey":"${references.reference(tenant)}","suiteRequestId":"${id.key.value}","occurredAt":"2026-11-09T12:00:00Z","type":"MESSAGE","message":{"messageId":"partner-1","kind":"REPLY","authorName":"Partner desk","authorEmail":null,"body":"We look into it"}}"""

        poll.pollForUpdates()

        assertThat(withMediator { GetSupportRequestComments(id).query() }.map { it.body }).contains("We look into it")
        val from = jdbi.withHandle<UUID?, Exception> { it.createQuery("SELECT from_connection FROM support_request_messages WHERE request_id = :id AND remote_id = 'partner-1'").bind("id", id.key.value).mapTo(UUID::class.java).one() }
        assertThat(from).isEqualTo(partnerId)
    }

    @Test
    fun `what is written while the tenant stops sharing with the partner never goes there`() {
        val tenant = createTenant("Partner Withdrawn").id
        share(tenant)
        withMediator { SetSupportRequestHandling(partnerId).execute() }
        val id = submit(tenant)
        sweep.retryPendingSync()
        withMediator { WithdrawSupportRequestSyncConsent(tenant, partnerId).execute() }
        val unshared = SupportRequestCommentKey.generate()
        withMediator { AddSupportRequestComment(SupportRequestCommentId(unshared, id), "Written while not shared", "Partner Requests", null).execute() }

        share(tenant)
        val shared = SupportRequestCommentKey.generate()
        withMediator { AddSupportRequestComment(SupportRequestCommentId(shared, id), "Written while shared", "Partner Requests", null).execute() }
        work.awaitIdle()
        sweep.retryPendingSync()

        assertThat(sentPaths()).anyMatch { it.endsWith("/messages/${shared.value}") }.noneMatch { it.endsWith("/messages/${unshared.value}") }
    }

    @Test
    fun `the desk passes a request to the partner, and what it holds goes along`() {
        val tenant = createTenant("Partner Escalated").id
        val id = submit(tenant)
        val earlier = SupportRequestCommentKey.generate()
        withMediator { AddSupportRequestComment(SupportRequestCommentId(earlier, id), "Before it went", "Partner Requests", null).execute() }
        share(tenant)

        withMediator { EscalateSupportRequest(tenant, id.key, partnerId).execute() }
        work.awaitIdle()
        sweep.retryPendingSync()

        assertThat(sentPaths()).contains("/api/v2/requests/${references.reference(tenant)}/${id.key.value}")
        assertThat(sentPaths()).anyMatch { it.endsWith("/messages/${earlier.value}") }
    }

    companion object {
        private const val TOKEN = "partner-requests-token"

        private val partner = FakeSupportServer()

        @JvmStatic
        @DynamicPropertySource
        fun partnerConnection(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.allow-http") { "true" }
            registry.add("epistola.support.connections[0].name") { "Acme Partner" }
            registry.add("epistola.support.connections[0].endpoint") { partner.baseUrl }
            registry.add("epistola.support.connections[0].token") { TOKEN }
            registry.add("epistola.support.connections[0].services") { "requests" }
            registry.add("epistola.support.requests.sync.enabled") { "true" }
            registry.add("epistola.support.requests.sync.retry-interval-ms") { "3600000" }
            registry.add("epistola.support.requests.sync.polling.enabled") { "true" }
            registry.add("epistola.support.requests.sync.polling.interval-ms") { "3600000" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = partner.close()
    }
}

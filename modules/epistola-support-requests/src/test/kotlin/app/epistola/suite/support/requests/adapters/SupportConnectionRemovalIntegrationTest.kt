// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.adapters

import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.common.ids.SupportRequestKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.connection.RemoveSupportConnection
import app.epistola.suite.support.connection.SupportBackgroundWork
import app.epistola.suite.support.connection.SupportConnectionRegistry
import app.epistola.suite.support.connection.SupportConnections
import app.epistola.suite.support.connection.TokenSupportConnection
import app.epistola.suite.support.privacy.TenantReferences
import app.epistola.suite.support.requests.SupportRequestCategory
import app.epistola.suite.support.requests.SupportRequestPriority
import app.epistola.suite.support.requests.commands.CreateSupportRequest
import app.epistola.suite.support.requests.consent.GrantSupportRequestSyncConsent
import app.epistola.suite.support.requests.desk.GetSupportInboxRequest
import app.epistola.suite.support.requests.desk.ResetSupportRequestHandling
import app.epistola.suite.support.requests.desk.SetSupportRequestHandling
import app.epistola.suite.support.requests.desk.SupportRequestHandlingPolicy
import app.epistola.suite.support.requests.queries.GetSupportRequestComments
import app.epistola.suite.support.requests.sync.SupportRequestPollScheduler
import app.epistola.suite.support.requests.sync.SupportRequestSyncScheduler
import app.epistola.suite.testing.FakeSupportResponse
import app.epistola.suite.testing.FakeSupportServer
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Removing a support connection that handles new requests, handles a request, sent a reply on it and
 * that another connection escalates to (ADR 0053 §1): new and handled requests come back to the inbox,
 * nothing more goes to it, its reply keeps its name, and the other escalates to no one.
 */
@Isolated("Who handles new support requests applies to the whole installation")
class SupportConnectionRemovalIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var registry: SupportConnectionRegistry

    @Autowired
    private lateinit var connections: SupportConnections

    @Autowired
    private lateinit var handling: SupportRequestHandlingPolicy

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

    private val author = UserKey.of("00000000-0000-0000-0000-9a7700000002")
    private val feed = CopyOnWriteArrayList<String>()

    private fun partner(): TokenSupportConnection = registry.tokenConnections().single { it.endpoint.toString() == partner.baseUrl.trimEnd('/') }

    @AfterEach
    fun inbox() {
        work.awaitIdle()
        withMediator { ResetSupportRequestHandling().execute() }
    }

    @Test
    fun `removing a connection hands its requests and new ones back to the inbox, keeps its replies' name, and clears escalation to it`() {
        ensureUser(author, "removal-requests", "removal-requests@epistola.test", "Removal Requests")
        partner.issuePartnerToken(TOKEN, setOf("requests"))
        partner.route("PUT", "/api/v2/requests/") { r ->
            if (r.path.contains("/messages/")) {
                FakeSupportResponse.json(201, """{"messageId":"pm-${r.path.substringAfterLast('/')}"}""")
            } else {
                FakeSupportResponse.json(201, """{"requestId":"pr-${r.path.substringAfterLast('/')}","url":null}""")
            }
        }
        partner.route("POST", "/api/v2/requests/changes") { _ ->
            val entries = feed.toList().also { feed.clear() }
            FakeSupportResponse.json(200, """{"changes":[${entries.joinToString(",")}],"hasMore":false,"lastSequence":${if (entries.isEmpty()) "null" else entries.size}}""")
        }
        partner().checkNow()
        val partnerId = partner().id
        val other = connections.findByEndpoint(OTHER)!!.id
        assertThat(connections.find(other)!!.escalatesTo).`as`("configured to escalate to the partner").isEqualTo(partnerId)

        val tenant: TenantKey = createTenant("Removal").id
        withMediator { GrantSupportRequestSyncConsent(tenant, partnerId).execute() }
        withMediator { SetSupportRequestHandling(partnerId).execute() }
        val id = SupportRequestId(SupportRequestKey.generate(), TenantId(tenant))
        withMediator { CreateSupportRequest(id, "Blank preview", "It stays blank", SupportRequestCategory.BUG, SupportRequestPriority.MEDIUM, null, null, null, author).execute() }
        work.awaitIdle()
        sweep.retryPendingSync()
        feed += """{"sequence":1,"requestId":"pr-${id.key.value}","tenantKey":"${references.reference(tenant)}","suiteRequestId":"${id.key.value}","occurredAt":"2026-11-09T12:00:00Z","type":"MESSAGE","message":{"messageId":"removal-1","kind":"REPLY","authorName":"Partner desk","authorEmail":null,"body":"We look into it"}}"""
        val sent = jdbi.withHandle<String?, Exception> { it.createQuery("SELECT state || ':' || COALESCE(remote_ref, '-') FROM support_request_deliveries WHERE request_id = :id").bind("id", id.key.value).mapTo(String::class.java).findOne().orElse(null) }
        assertThat(sent).`as`("sent to the partner: " + partner.requests.map { it.method + " " + it.path }).isEqualTo("SYNCED:pr-${id.key.value}")
        poll.pollForUpdates()
        assertThat(withMediator { GetSupportRequestComments(id).query() }.map { it.body }).`as`("the partner's reply arrived").contains("We look into it")

        assertThat(withMediator { RemoveSupportConnection(partnerId).execute() }).isTrue()

        assertThat(handling.connectionForNewRequests()).`as`("new requests: the inbox").isNull()
        val handler = jdbi.withHandle<UUID?, Exception> { it.createQuery("SELECT handler_connection FROM support_requests WHERE id = :id").bind("id", id.key.value).mapTo(UUID::class.java).one() }
        assertThat(handler).`as`("the request it handled: the inbox").isNull()
        val deliveries = jdbi.withHandle<Int, Exception> { it.createQuery("SELECT COUNT(*) FROM support_request_deliveries WHERE request_id = :id").bind("id", id.key.value).mapTo(Int::class.java).one() }
        assertThat(deliveries).`as`("nothing more goes to it").isZero()

        val reply = withMediator { GetSupportRequestComments(id).query() }.single { it.body == "We look into it" }
        assertThat(reply.source.name).`as`("not as written here").isEqualTo("EXTERNAL")
        val desk = withMediator { GetSupportInboxRequest(tenant, id.key).query() }!!
        assertThat(desk.messages.single { it.body == "We look into it" }.fromConnectionName).isEqualTo("Acme Partner (removed)")

        assertThat(connections.find(other)!!.escalatesTo).`as`("the other escalates to no one").isNull()
    }

    companion object {
        private const val TOKEN = "removal-partner-token"
        private const val OTHER = "https://desk.other.example"

        private val partner = FakeSupportServer()

        @JvmStatic
        @DynamicPropertySource
        fun partnerConnection(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.allow-http") { "true" }
            registry.add("epistola.support.connections[0].name") { "Acme Partner" }
            registry.add("epistola.support.connections[0].endpoint") { partner.baseUrl }
            registry.add("epistola.support.connections[0].token") { TOKEN }
            registry.add("epistola.support.connections[0].services") { "requests" }
            registry.add("epistola.support.connections[1].name") { "Other Partner" }
            registry.add("epistola.support.connections[1].endpoint") { OTHER }
            registry.add("epistola.support.connections[1].token") { "other-token" }
            registry.add("epistola.support.connections[1].escalates-to") { partner.baseUrl }
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

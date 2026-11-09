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
import app.epistola.suite.support.connection.SupportBackgroundWork
import app.epistola.suite.support.connection.SupportConnectionRegistry
import app.epistola.suite.support.connection.TokenSupportConnection
import app.epistola.suite.support.connection.UpdateSupportConnection
import app.epistola.suite.support.privacy.TenantReferences
import app.epistola.suite.support.requests.SupportRequestCategory
import app.epistola.suite.support.requests.SupportRequestPriority
import app.epistola.suite.support.requests.commands.CreateSupportRequest
import app.epistola.suite.support.requests.consent.GrantSupportRequestSyncConsent
import app.epistola.suite.support.requests.desk.GetSupportInboxRequest
import app.epistola.suite.support.requests.desk.ResetSupportRequestHandling
import app.epistola.suite.support.requests.desk.SetSupportRequestHandling
import app.epistola.suite.support.requests.sync.SupportRequestPollScheduler
import app.epistola.suite.support.requests.sync.SupportRequestSyncScheduler
import app.epistola.suite.testing.FakeSupportRequest
import app.epistola.suite.testing.FakeSupportResponse
import app.epistola.suite.testing.FakeSupportServer
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A connection asks the suite, through the protocol, to escalate a request it handles (ADR 0053 §4):
 * the connection it escalates to is attached, as the desk would, and the desk is told in a note; when
 * that cannot be done, the note says why. A first line that escalates to a second line, both stand-ins.
 */
@Isolated("Who handles new support requests applies to the whole installation")
class SupportRequestEscalationIntegrationTest : IntegrationTestBase() {
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

    private val author = UserKey.of("00000000-0000-0000-0000-e5ca1a7e0001")

    private val feed = CopyOnWriteArrayList<String>()

    private fun connection(server: FakeSupportServer): TokenSupportConnection = registry.tokenConnections().single { it.endpoint.toString() == server.baseUrl.trimEnd('/') }

    private fun answer(server: FakeSupportServer, prefix: String, withFeed: Boolean) {
        val scoped = { r: FakeSupportRequest, a: () -> FakeSupportResponse -> if (r.clientId == null) FakeSupportResponse.invalidToken() else a() }
        server.route("PUT", "/api/v2/requests/") { r ->
            scoped(r) {
                if (r.path.contains("/messages/")) {
                    FakeSupportResponse.json(201, """{"messageId":"$prefix-m-${r.path.substringAfterLast('/')}"}""")
                } else {
                    FakeSupportResponse.json(201, """{"requestId":"$prefix-${r.path.substringAfterLast('/')}","url":null}""")
                }
            }
        }
        server.route("POST", "/api/v2/requests/changes") { r ->
            scoped(r) {
                val entries = if (withFeed) feed.toList().also { feed.clear() } else emptyList()
                FakeSupportResponse.json(200, """{"changes":[${entries.joinToString(",")}],"hasMore":false,"lastSequence":${if (entries.isEmpty()) "null" else entries.size}}""")
            }
        }
    }

    @BeforeEach
    fun connect() {
        ensureUser(author, "escalation-author", "escalation@epistola.test", "Escalation Author")
        firstLine.issuePartnerToken(FIRST_TOKEN, setOf("requests"))
        secondLine.issuePartnerToken(SECOND_TOKEN, setOf("requests"))
        answer(firstLine, "first", withFeed = true)
        answer(secondLine, "second", withFeed = false)
        connection(firstLine).checkNow()
        connection(secondLine).checkNow()
        firstLine.requests.clear()
        secondLine.requests.clear()
    }

    @AfterEach
    fun inbox() {
        work.awaitIdle()
        withMediator { ResetSupportRequestHandling().execute() }
    }

    private fun handledByFirstLine(tenant: TenantKey): SupportRequestId {
        withMediator { GrantSupportRequestSyncConsent(tenant, connection(firstLine).id).execute() }
        withMediator { SetSupportRequestHandling(connection(firstLine).id).execute() }
        val id = SupportRequestId(SupportRequestKey.generate(), TenantId(tenant))
        withMediator { CreateSupportRequest(id, "Needs the second line", "It stays blank", SupportRequestCategory.BUG, SupportRequestPriority.HIGH, null, null, null, author).execute() }
        work.awaitIdle()
        sweep.retryPendingSync()
        return id
    }

    private fun escalation(tenant: TenantKey, id: SupportRequestId, reason: String) = """{"sequence":1,"requestId":"first-${id.key.value}","tenantKey":"${references.reference(tenant)}","suiteRequestId":"${id.key.value}","occurredAt":"2026-11-09T12:00:00Z","type":"ESCALATION","reason":"$reason"}"""

    private fun notes(id: SupportRequestId): List<String> = withMediator { GetSupportInboxRequest(id.tenantKey, id.key).query()!! }.messages.filter { it.note }.map { it.body }

    @Test
    fun `an escalation from the first line attaches the second line, which gets the request, and the desk is told`() {
        val tenant = createTenant("Escalated To Second Line").id
        val id = handledByFirstLine(tenant)
        withMediator { GrantSupportRequestSyncConsent(tenant, connection(secondLine).id).execute() }
        feed += escalation(tenant, id, "Looks like a product bug")

        poll.pollForUpdates()
        work.awaitIdle()
        sweep.retryPendingSync()

        assertThat(secondLine.requests.filter { it.method == "PUT" }.map { it.path }).contains("/api/v2/requests/${references.reference(tenant)}/${id.key.value}")
        assertThat(notes(id)).anyMatch { it.contains("asked to escalate this request: Looks like a product bug") && it.contains("It went to Second line as well") }
        assertThat(firstLine.requests.filter { it.method == "PUT" }.map { it.bodyText }).noneMatch { it.contains("asked to escalate") }
    }

    @Test
    fun `when the tenant does not share with the second line, the desk is told it could not go there`() {
        val tenant = createTenant("Not Escalated").id
        val id = handledByFirstLine(tenant)
        feed += escalation(tenant, id, "Please look")

        poll.pollForUpdates()

        assertThat(secondLine.requests.filter { it.method == "PUT" }).isEmpty()
        assertThat(notes(id)).anyMatch { it.contains("It could not go to Second line") }
    }

    @Test
    fun `without a connection to escalate to, the desk is asked to act`() {
        val tenant = createTenant("Nowhere To Escalate").id
        val id = handledByFirstLine(tenant)
        val first = connection(firstLine)
        withMediator { UpdateSupportConnection(first.id, "First line", listOf("requests"), escalatesTo = null, changeEscalation = true).execute() }
        feed += escalation(tenant, id, "Please look")

        poll.pollForUpdates()

        assertThat(notes(id)).anyMatch { it.contains("No connection is set for First line to escalate to") }
    }

    companion object {
        private const val FIRST_TOKEN = "first-line-token"
        private const val SECOND_TOKEN = "second-line-token"

        private val firstLine = FakeSupportServer()
        private val secondLine = FakeSupportServer()

        @JvmStatic
        @DynamicPropertySource
        fun connections(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.allow-http") { "true" }
            registry.add("epistola.support.connections[0].name") { "First line" }
            registry.add("epistola.support.connections[0].endpoint") { firstLine.baseUrl }
            registry.add("epistola.support.connections[0].token") { FIRST_TOKEN }
            registry.add("epistola.support.connections[0].services") { "requests" }
            registry.add("epistola.support.connections[0].escalates-to") { secondLine.baseUrl }
            registry.add("epistola.support.connections[1].name") { "Second line" }
            registry.add("epistola.support.connections[1].endpoint") { secondLine.baseUrl }
            registry.add("epistola.support.connections[1].token") { SECOND_TOKEN }
            registry.add("epistola.support.connections[1].services") { "requests" }
            registry.add("epistola.support.requests.sync.enabled") { "true" }
            registry.add("epistola.support.requests.sync.retry-interval-ms") { "3600000" }
            registry.add("epistola.support.requests.sync.polling.enabled") { "true" }
            registry.add("epistola.support.requests.sync.polling.interval-ms") { "3600000" }
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            firstLine.close()
            secondLine.close()
        }
    }
}

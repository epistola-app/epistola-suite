// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.desk

import app.epistola.suite.common.PageRequest
import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.common.ids.SupportRequestKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.connection.SupportBackgroundWork
import app.epistola.suite.support.requests.RecordingSupportRequestSyncPort
import app.epistola.suite.support.requests.SupportRequest
import app.epistola.suite.support.requests.SupportRequestCategory
import app.epistola.suite.support.requests.SupportRequestPriority
import app.epistola.suite.support.requests.SupportRequestStatus
import app.epistola.suite.support.requests.SupportRequestSyncDriverIntegrationTest
import app.epistola.suite.support.requests.SyncStatus
import app.epistola.suite.support.requests.commands.CreateSupportRequest
import app.epistola.suite.support.requests.commands.SyncSupportRequestStatus
import app.epistola.suite.support.requests.consent.GrantSupportRequestSyncConsent
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION
import app.epistola.suite.support.requests.consent.WithdrawSupportRequestSyncConsent
import app.epistola.suite.support.requests.desk.ResetSupportRequestHandling
import app.epistola.suite.support.requests.queries.GetSupportRequest
import app.epistola.suite.support.requests.queries.GetSupportRequestComments
import app.epistola.suite.support.requests.sync.SupportRequestSyncPort
import app.epistola.suite.support.requests.sync.SupportRequestSyncScheduler
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Isolated
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource

/**
 * The support desk (ADR 0053 §3–4): who handles new requests, the inbox across tenants, internal
 * notes, the handler rule for status and priority, and passing a request on. Against the same
 * recording stand-in for the support service as [SupportRequestSyncDriverIntegrationTest].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(SupportRequestSyncDriverIntegrationTest.TestConfig::class)
@TestPropertySource(
    properties = [
        "epistola.support.requests.sync.enabled=true",
        "epistola.support.requests.sync.retry-interval-ms=3600000",
        "epistola.support.requests.sync.polling.enabled=true",
        "epistola.support.requests.sync.polling.interval-ms=3600000",
    ],
)
@Isolated("Who handles new support requests applies to the whole installation")
class SupportDeskIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var syncPort: SupportRequestSyncPort

    @Autowired
    lateinit var sweep: SupportRequestSyncScheduler

    @Autowired
    lateinit var work: SupportBackgroundWork

    private val recording get() = syncPort as RecordingSupportRequestSyncPort

    private val author = UserKey.of("00000000-0000-0000-0000-de5c00000001")

    @BeforeEach
    fun setUp() {
        recording.reset()
        ensureUser(author, "support-desk-author", "support-desk@epistola.test", "Desk Author")
    }

    @AfterEach
    fun inboxHandlesNewRequests() {
        withMediator { ResetSupportRequestHandling().execute() }
    }

    private fun submit(tenant: Tenant, title: String = "Desk request"): SupportRequest = withMediator {
        val created = CreateSupportRequest(
            id = SupportRequestId(SupportRequestKey.generate(), TenantId(tenant.id)),
            title = title,
            description = "It does not work",
            category = SupportRequestCategory.BUG,
            priority = SupportRequestPriority.MEDIUM,
            sourceUrl = null,
            consoleLogs = null,
            metadata = null,
            createdBy = author,
        ).execute()
        work.awaitIdle()
        GetSupportRequest(SupportRequestId(created.id, TenantId(tenant.id))).query()!!
    }

    private fun reload(request: SupportRequest): SupportRequest = withMediator { GetSupportRequest(SupportRequestId(request.id, TenantId(request.tenantKey))).query()!! }

    private fun sharing(name: String): Tenant = createTenant(name).also { withMediator { GrantSupportRequestSyncConsent(it.id).execute() } }

    @Test
    fun `new requests stay in the inbox by default, also when the tenant shares them`() {
        val request = submit(sharing("Desk Default"))

        assertThat(request.handledHere).isTrue()
        assertThat(request.syncStatus).isEqualTo(SyncStatus.NOT_CONFIGURED)
        assertThat(recording.createdTickets).isEmpty()
        assertThat(withMediator { GetSupportRequestHandling().query() }.handledHere).isTrue()
    }

    @Test
    fun `a saved choice wins over the configuration until it is reset`() {
        val configured = withMediator { GetSupportRequestHandling().query() }
        assertThat(configured.fromConfiguration).isTrue()
        assertThat(configured.handledHere).`as`("the configuration's default: the inbox").isTrue()

        val saved = withMediator { SetSupportRequestHandling(REQUESTS_CONNECTION).execute() }
        assertThat(saved.fromConfiguration).isFalse()
        assertThat(saved.connectionId).isEqualTo(REQUESTS_CONNECTION)
        assertThat(saved.connectionName).isEqualTo("Epistola support")

        val reset = withMediator { ResetSupportRequestHandling().execute() }
        assertThat(reset.fromConfiguration).isTrue()
        assertThat(reset.handledHere).isTrue()
    }

    @Test
    fun `with Epistola handling new requests a new one goes there, and switching back leaves it there`() {
        val tenant = sharing("Desk Handling")
        val handling = withMediator { SetSupportRequestHandling(REQUESTS_CONNECTION).execute() }
        assertThat(handling.connectionId).isEqualTo(REQUESTS_CONNECTION)

        val atEpistola = submit(tenant, "Goes to Epistola")
        assertThat(atEpistola.handlerConnection).isEqualTo(REQUESTS_CONNECTION)
        assertThat(recording.createdTickets.map { it.id }).containsExactly(atEpistola.id)

        withMediator { SetSupportRequestHandling(null).execute() }
        val inInbox = submit(tenant, "Stays here")

        assertThat(reload(atEpistola).handlerConnection).`as`("already at Epistola: stays there").isEqualTo(REQUESTS_CONNECTION)
        assertThat(inInbox.handledHere).isTrue()
        assertThat(recording.createdTickets.map { it.id }).containsExactly(atEpistola.id)
    }

    @Test
    fun `a request still waiting to go to Epistola comes back to the inbox when its tenant stops sharing`() {
        val tenant = sharing("Desk Withdrawn Waiting")
        withMediator { SetSupportRequestHandling(REQUESTS_CONNECTION).execute() }
        recording.failCreate = true
        val waiting = submit(tenant)
        assertThat(reload(waiting).handlerConnection).isEqualTo(REQUESTS_CONNECTION)

        withMediator { WithdrawSupportRequestSyncConsent(tenant.id).execute() }

        assertThat(reload(waiting).handlerConnection).`as`("never sent, so the inbox handles it").isNull()
    }

    @Test
    fun `with nothing to send them over, the desk says new requests stay here, as they do`() {
        withMediator { SetSupportRequestHandling(REQUESTS_CONNECTION).execute() }
        recording.enabled = false

        val handling = withMediator { GetSupportRequestHandling().query() }
        val created = submit(sharing("Desk Not Wired"))

        assertThat(handling.connectionId).`as`("handled here").isNull()
        assertThat(reload(created).handlerConnection).isNull()
    }

    @Test
    fun `internal notes never leave the installation, replies go with a request passed on`() {
        val tenant = sharing("Desk Notes")
        val request = submit(tenant)
        withMediator {
            AddSupportDeskMessage(tenant.id, request.id, "We reproduced it", note = false).execute()
            AddSupportDeskMessage(tenant.id, request.id, "Customer is on an old browser", note = true).execute()
        }
        work.awaitIdle()
        assertThat(recording.createdTickets).`as`("not passed on yet").isEmpty()

        withMediator { EscalateSupportRequest(tenant.id, request.id, REQUESTS_CONNECTION).execute() }
        work.awaitIdle()
        sweep.retryPendingSync()

        assertThat(recording.createdTickets.map { it.id }).containsExactly(request.id)
        assertThat(recording.addedComments.map { it.second.body }).containsExactly("We reproduced it")
        assertThat(recording.addedComments.single().second.authorId).`as`("the desk's author, for the person reference").isNotNull()
        assertThat(reload(request).handlerConnection).isEqualTo(REQUESTS_CONNECTION)
        val requesterSees = withMediator { GetSupportRequestComments(SupportRequestId(request.id, TenantId(tenant.id))).query() }
        assertThat(requesterSees.map { it.body }).`as`("the requester sees replies, not notes").containsExactly("We reproduced it")
        val deskSees = withMediator { GetSupportInboxRequest(tenant.id, request.id).query() }!!
        assertThat(deskSees.messages.map { it.body to it.note }).containsExactly("We reproduced it" to false, "Customer is on an old browser" to true)
    }

    @Test
    fun `a request submitted while the tenant did not share cannot be passed on, nor one whose tenant stopped sharing`() {
        val notSharing = createTenant("Desk Not Sharing")
        val before = submit(notSharing)
        val view = withMediator { GetSupportInboxRequest(notSharing.id, before.id).query() }!!
        assertThat(view.canEscalate).isFalse()
        assertThat(view.escalationBlocked).contains("did not share support requests when this one was submitted")
        assertThatThrownBy { withMediator { EscalateSupportRequest(notSharing.id, before.id, REQUESTS_CONNECTION).execute() } }
            .isInstanceOf(SupportRequestNotEscalatedException::class.java)

        val stopped = sharing("Desk Stopped Sharing")
        val submitted = submit(stopped)
        withMediator { WithdrawSupportRequestSyncConsent(stopped.id).execute() }
        assertThat(withMediator { GetSupportInboxRequest(stopped.id, submitted.id).query() }!!.escalationBlocked).contains("does not share support requests now")
        assertThatThrownBy { withMediator { EscalateSupportRequest(stopped.id, submitted.id, REQUESTS_CONNECTION).execute() } }
            .isInstanceOf(SupportRequestNotEscalatedException::class.java)
        assertThat(recording.createdTickets).isEmpty()
    }

    @Test
    fun `only the handler sets status and priority`() {
        val tenant = sharing("Desk Handler Rule")
        val request = submit(tenant)
        val id = SupportRequestId(request.id, TenantId(tenant.id))

        assertThat(withMediator { SyncSupportRequestStatus(id, SupportRequestStatus.RESOLVED).execute() }).`as`("a connection does not handle it").isFalse()
        assertThat(withMediator { SetSupportDeskStatus(tenant.id, request.id, SupportRequestStatus.IN_PROGRESS).execute() }).isTrue()
        assertThat(reload(request).status).isEqualTo(SupportRequestStatus.IN_PROGRESS)

        withMediator { EscalateSupportRequest(tenant.id, request.id, REQUESTS_CONNECTION).execute() }
        work.awaitIdle()

        assertThatThrownBy { withMediator { SetSupportDeskStatus(tenant.id, request.id, SupportRequestStatus.CLOSED).execute() } }
            .isInstanceOf(SupportRequestHandledElsewhereException::class.java)
        assertThatThrownBy { withMediator { SetSupportDeskPriority(tenant.id, request.id, SupportRequestPriority.LOW).execute() } }
            .isInstanceOf(SupportRequestHandledElsewhereException::class.java)
        assertThat(withMediator { SyncSupportRequestStatus(id, SupportRequestStatus.RESOLVED).execute() }).`as`("its handler sets it").isTrue()
        assertThat(reload(request).status).isEqualTo(SupportRequestStatus.RESOLVED)
    }

    @Test
    fun `the inbox lists requests across tenants, filtered and paged in the database`() {
        val first = createTenant("Desk Inbox One")
        val second = sharing("Desk Inbox Two")
        submit(first, "One a")
        submit(first, "One b")
        val passedOn = submit(second, "Two a")
        withMediator { EscalateSupportRequest(second.id, passedOn.id, REQUESTS_CONNECTION).execute() }
        work.awaitIdle()

        val ofFirst = withMediator { ListSupportInbox(tenant = first.id, page = PageRequest(limit = 1, offset = 0)).query() }
        assertThat(ofFirst.total).isEqualTo(2)
        assertThat(ofFirst.items.map { it.title }).containsExactly("One b")
        assertThat(withMediator { ListSupportInbox(tenant = first.id, page = PageRequest(limit = 1, offset = 1)).query() }.items.map { it.title }).containsExactly("One a")

        val elsewhere = withMediator { ListSupportInbox(tenant = second.id, handler = SupportInboxHandler.ELSEWHERE).query() }
        assertThat(elsewhere.items.map { it.title to it.handlerName }).containsExactly("Two a" to "Epistola support")
        assertThat(withMediator { ListSupportInbox(tenant = second.id, handler = SupportInboxHandler.HERE).query() }.total).isZero()
        assertThat(withMediator { ListSupportInbox(tenant = first.id, status = SupportRequestStatus.CLOSED).query() }.total).isZero()
        assertThat(ofFirst.items.single().tenantName).isEqualTo("Desk Inbox One")
    }
}

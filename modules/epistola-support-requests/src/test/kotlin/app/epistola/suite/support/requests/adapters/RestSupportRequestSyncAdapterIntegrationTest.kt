// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.adapters

import app.epistola.suite.common.ids.SupportRequestAssetKey
import app.epistola.suite.common.ids.SupportRequestCommentKey
import app.epistola.suite.common.ids.SupportRequestKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.support.connection.DefaultSupportConnection
import app.epistola.suite.support.connection.SupportConnectionState
import app.epistola.suite.support.connection.isSupportUnavailable
import app.epistola.suite.support.privacy.SetSupportPrivacy
import app.epistola.suite.support.privacy.SupportPrivacy
import app.epistola.suite.support.privacy.TelemetryDetail
import app.epistola.suite.support.privacy.TenantIdentity
import app.epistola.suite.support.privacy.TenantReferences
import app.epistola.suite.support.requests.CommentSource
import app.epistola.suite.support.requests.SupportRequest
import app.epistola.suite.support.requests.SupportRequestAssetContent
import app.epistola.suite.support.requests.SupportRequestCategory
import app.epistola.suite.support.requests.SupportRequestComment
import app.epistola.suite.support.requests.SupportRequestPriority
import app.epistola.suite.support.requests.SupportRequestStatus
import app.epistola.suite.support.requests.SyncStatus
import app.epistola.suite.support.requests.sync.ExternalUpdate
import app.epistola.suite.support.requests.sync.SupportRequestSyncNotReadyException
import app.epistola.suite.support.requests.sync.SupportRequestSyncPort
import app.epistola.suite.support.requests.sync.isSupportRequestSyncDeferred
import app.epistola.suite.testing.FakeSupportRequest
import app.epistola.suite.testing.FakeSupportResponse
import app.epistola.suite.testing.FakeSupportServer
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The REST feedback adapter against a stand-in support service over real HTTP
 * (`docs/support-protocol.md`, Feedback): paths, bodies, the bearer token, idempotent ids, and how
 * failures are classified for the drivers.
 */
class RestSupportRequestSyncAdapterIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var port: SupportRequestSyncPort

    @Autowired
    private lateinit var connection: DefaultSupportConnection

    @Autowired
    private lateinit var references: TenantReferences

    @Autowired
    private lateinit var privacy: SupportPrivacy

    @Autowired
    private lateinit var jdbi: org.jdbi.v3.core.Jdbi

    private val adapter get() = port as RestSupportRequestSyncAdapter

    @BeforeEach
    fun connectWithSupportRequest() {
        if (connection.status().state !is SupportConnectionState.Pending && connection.status().state !is SupportConnectionState.Active) {
            connection.checkNow()
        }
        support.activate(scopes = setOf("requests"))
        support.expireTokens()
        connection.checkNow()
        // Every requests endpoint answers only a token with the requests scope, like the service.
        support.route("PUT", "/api/v2/requests/") { r -> scoped(r) { FakeSupportResponse.json(201, """{"requestId":"0193-fb","url":null}""") } }
        support.route("PUT", "/api/v2/requests/0193-fb/messages/") { r -> scoped(r) { FakeSupportResponse.json(201, """{"messageId":"0193-c"}""") } }
        support.route("PUT", "/api/v2/requests/0193-fb/attachments/") { r -> scoped(r) { FakeSupportResponse.json(201, """{"attachmentId":"0193-a"}""") } }
    }

    @Test
    fun `the port is the REST adapter while the support connection is on, ready while support request is granted`() {
        assertThat(port).isInstanceOf(RestSupportRequestSyncAdapter::class.java)
        assertThat(port.isEnabled()).isTrue()
        assertThat(port.isReady()).isTrue()

        support.activate(scopes = setOf("telemetry"))
        connection.checkNow()
        assertThat(port.isReady()).`as`("not latched: a scope taken away stops sending").isFalse()

        support.activate(scopes = setOf("telemetry", "requests"))
        connection.checkNow()
        assertThat(port.isReady()).isTrue()
    }

    @Test
    fun `with confidential tenants, support request goes under the tenant's reference, which also replaces it in the page address`() {
        withMediator { SetSupportPrivacy(TenantIdentity.CONFIDENTIAL, TelemetryDetail.PSEUDONYMISED).execute() }
        try {
            val reference = references.reference(TENANT)
            val supportRequest = supportRequest(metadata = """{"url":"https://suite.example.org/tenants/acme-fb/templates"}""")

            port.createTicket(supportRequest)

            assertThat(reference).matches("t-[0-9a-f]{16}").isEqualTo(references.reference(TENANT))
            val request = support.requests.last { it.method == "PUT" && it.path.startsWith("/api/v2/requests/$reference/") }
            assertThat(request.path).isEqualTo("/api/v2/requests/$reference/${supportRequest.id.value}")
            val body = request.json()
            assertThat(body.path("sourceUrl").stringValue()).isEqualTo("https://suite.example.org/tenants/$reference/templates?q=letter")
            assertThat(body.path("metadata").stringValue()).doesNotContain("acme-fb").contains("/tenants/$reference/templates")
        } finally {
            // No command removes saved settings; the next test must find the configuration in force.
            jdbi.useHandle<Exception> { it.createUpdate("DELETE FROM app_metadata WHERE key = :key").bind("key", SupportPrivacy.KEY).execute() }
            privacy.refresh()
        }
    }

    @Test
    fun `with confidential tenants and full telemetry, an attached server log names the tenant by its reference only`() {
        withMediator { SetSupportPrivacy(TenantIdentity.CONFIDENTIAL, TelemetryDetail.FULL).execute() }
        try {
            val reference = references.reference(TENANT)
            val supportRequest = supportRequest(serverLogs = """[{"level":"ERROR","message":"Rendering invoice for tenant acme-fb failed: Tenant acme-fb not found"}]""")

            port.createTicket(supportRequest)

            val body = support.requests.last { it.method == "PUT" && it.path.startsWith("/api/v2/requests/$reference/") }.json()
            assertThat(body.path("serverLogs").stringValue()).doesNotContain("acme-fb").contains("tenant $reference failed")
        } finally {
            jdbi.useHandle<Exception> { it.createUpdate("DELETE FROM app_metadata WHERE key = :key").bind("key", SupportPrivacy.KEY).execute() }
            privacy.refresh()
        }
    }

    @Test
    fun `a support request is sent with its own id, every field and a bearer token, then its question`() {
        val supportRequest = supportRequest(
            consoleLogs = """[{"level":"error","message":"boom"}]""",
            metadata = """{"viewport":"1280x800"}""",
            serverLogs = """[{"level":"ERROR","message":"Render failed for {}"}]""",
        )

        val result = port.createTicket(supportRequest)
        port.createTicket(supportRequest)

        assertThat(result.externalRef).isEqualTo("0193-fb")
        assertThat(result.externalUrl).isEmpty()
        val sent = support.requests.filter { it.method == "PUT" && it.path.startsWith("/api/v2/requests/acme-fb/") }
        assertThat(sent.map { it.path }).`as`("a repeat goes to the same id").containsExactly(
            "/api/v2/requests/acme-fb/${supportRequest.id.value}",
            "/api/v2/requests/acme-fb/${supportRequest.id.value}",
        )
        val request = sent.first()
        assertThat(request.bearerToken).isNotNull()
        assertThat(request.tokenScopes).contains("requests")
        assertThat(request.header("Content-Type")).startsWith("application/json")
        val body = request.json()
        assertThat(body.path("title").stringValue()).isEqualTo("Preview does not load")
        assertThat(body.path("serverLogs").stringValue()).isEqualTo("""[{"level":"ERROR","message":"Render failed for {}"}]""")
        assertThat(body.has("description")).`as`("the description is the question, a message of its own").isFalse()
        assertThat(body.path("kind").stringValue()).isEqualTo("BUG")
        assertThat(body.path("requestedPriority").stringValue()).isEqualTo("HIGH")
        assertThat(body.path("status").stringValue()).isEqualTo("OPEN")
        assertThat(body.path("sourceUrl").stringValue()).isEqualTo("https://suite.example.org/tenants/acme-fb/templates?q=letter")
        assertThat(body.path("consoleLogs").stringValue()).isEqualTo("""[{"level":"error","message":"boom"}]""")
        assertThat(body.path("metadata").stringValue()).isEqualTo("""{"viewport":"1280x800"}""")
        assertThat(body.path("createdAt").stringValue()).isEqualTo("2026-11-05T13:59:00Z")
        val questions = support.requests.filter { it.method == "PUT" && it.path.startsWith("/api/v2/requests/0193-fb/messages/") }
        assertThat(questions.map { it.path }.distinct()).`as`("the question has one id, also on a repeat").hasSize(1)
        assertThat(questions).hasSize(2)
        val question = questions.first().json()
        assertThat(question.path("kind").stringValue()).isEqualTo("QUESTION")
        assertThat(question.path("body").stringValue()).isEqualTo("It spins")
        assertThat(question.has("authorName")).`as`("who asked stays here").isFalse()
    }

    @Test
    fun `optional fields are left out, and a url is passed on`() {
        support.route("PUT", "/api/v2/requests/acme-fb/") { r -> scoped(r) { FakeSupportResponse.json(200, """{"requestId":"0193-x","url":"https://support.example/f/1"}""") } }
        support.route("PUT", "/api/v2/requests/0193-x/messages/") { r -> scoped(r) { FakeSupportResponse.json(200, """{"messageId":"0193-q"}""") } }
        val supportRequest = supportRequest(sourceUrl = null)

        val result = port.createTicket(supportRequest)

        assertThat(result.externalUrl).isEqualTo("https://support.example/f/1")
        val body = support.requests.last { it.path.endsWith(supportRequest.id.value.toString()) }.json()
        assertThat(body.has("sourceUrl")).isFalse()
        assertThat(body.has("consoleLogs")).isFalse()
        assertThat(body.has("metadata")).isFalse()
    }

    @Test
    fun `an asset goes as raw bytes under the support service's support request id and its own id`() {
        val assetKey = SupportRequestAssetKey.generate()

        port.uploadAsset(synced(), assetKey, SupportRequestAssetContent(byteArrayOf(1, 2, 3), "image/jpeg", "screenshot.jpeg"))

        val request = support.requests.last { it.path.contains("/attachments/") }
        assertThat(request.path).isEqualTo("/api/v2/requests/0193-fb/attachments/${assetKey.value}")
        assertThat(request.header("Content-Type")).isEqualTo("image/jpeg")
        assertThat(request.header("X-Epistola-Filename")).isEqualTo("screenshot.jpeg")
        assertThat(request.body).containsExactly(1, 2, 3)
        assertThat(request.bearerToken).isNotNull()
    }

    @Test
    fun `an asset of a type the service does not take is refused before sending`() {
        val failure = catchThrowable {
            port.uploadAsset(synced(), SupportRequestAssetKey.generate(), SupportRequestAssetContent(byteArrayOf(1), "image/gif", "screenshot.gif"))
        }

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure.isSupportRequestSyncDeferred()).`as`("counts an attempt").isFalse()
    }

    @Test
    fun `a comment goes as a reply with its own id and author, a status change as a status message`() {
        val comment = SupportRequestComment(
            id = SupportRequestCommentKey.generate(),
            requestId = SupportRequestKey.generate(),
            tenantKey = TENANT,
            body = "Still broken",
            authorName = "Jan Jansen",
            authorEmail = "jan@voorbeeld.nl",
            source = CommentSource.LOCAL,
            externalCommentId = null,
            createdAt = CREATED_AT,
            authorId = UserKey.of("00000000-0000-0000-0000-feedbac00096").value,
        )

        val sent = synced()
        val ref = port.addComment(sent, comment)
        port.updateStatus(sent, SupportRequestStatus.CLOSED)

        assertThat(ref.externalCommentId).isEqualTo("0193-c")
        val commentRequest = support.requests.single { it.path == "/api/v2/requests/0193-fb/messages/${comment.id.value}" }
        val body = commentRequest.json()
        assertThat(body.path("kind").stringValue()).isEqualTo("REPLY")
        assertThat(body.path("authorName").stringValue()).isEqualTo("Jan Jansen")
        assertThat(body.path("authorEmail").stringValue()).isEqualTo("jan@voorbeeld.nl")
        assertThat(body.path("authorRef").stringValue()).`as`("named tenants: the name, and the reference too").matches("p-[0-9a-f]{16}")
        assertThat(body.path("body").stringValue()).isEqualTo("Still broken")
        assertThat(body.path("createdAt").stringValue()).isEqualTo("2026-11-05T13:59:00Z")
        val statusRequest = support.requests.last { it.path.startsWith("/api/v2/requests/0193-fb/messages/") }
        assertThat(statusRequest.json().path("kind").stringValue()).isEqualTo("STATUS")
        assertThat(statusRequest.json().path("status").stringValue()).isEqualTo("CLOSED")
        port.updateStatus(sent, SupportRequestStatus.CLOSED)
        assertThat(support.requests.last { it.path.startsWith("/api/v2/requests/0193-fb/messages/") }.path)
            .`as`("a repeat of the same change is the same message").isEqualTo(statusRequest.path)
    }

    @Test
    fun `a request names who submitted it by a person reference, the same for every request of that person`() {
        val first = supportRequest()
        val second = supportRequest()
        val other = supportRequest().copy(createdBy = UserKey.of("00000000-0000-0000-0000-feedbac00094"))

        listOf(first, second, other).forEach { port.createTicket(it) }

        val sent = listOf(first, second, other).map { r -> support.requests.last { it.method == "PUT" && it.path.endsWith("/$TENANT/${r.id.value}") }.json() }
        val refs = sent.map { it.path("requesterRef").stringValue() }
        assertThat(refs[0]).matches("p-[0-9a-f]{16}").isEqualTo(refs[1]).isNotEqualTo(refs[2])
        assertThat(refs[0]).isEqualTo(references.personReference(first.createdBy.value))
            .isNotEqualTo(references.reference(first.createdBy.value.toString()))
        assertThat(sent[0].toString()).doesNotContain("00000000-0000-0000-0000-feedbac00093")
    }

    @Test
    fun `with confidential tenants a reply names its author by a person reference only`() {
        val author = UserKey.of("00000000-0000-0000-0000-feedbac00095")
        val comment = SupportRequestComment(
            id = SupportRequestCommentKey.generate(),
            requestId = SupportRequestKey.generate(),
            tenantKey = TENANT,
            body = "Still broken",
            authorName = "Jan Jansen",
            authorEmail = "jan@voorbeeld.nl",
            source = CommentSource.LOCAL,
            externalCommentId = null,
            createdAt = CREATED_AT,
            authorId = author.value,
        )
        val anonymous = comment.copy(id = SupportRequestCommentKey.generate(), authorId = null)
        withMediator { SetSupportPrivacy(TenantIdentity.CONFIDENTIAL, TelemetryDetail.PSEUDONYMISED).execute() }
        try {
            port.addComment(synced(), comment)
            port.addComment(synced(), anonymous)

            val body = support.requests.single { it.path.endsWith("/messages/${comment.id.value}") }.json()
            assertThat(body.path("authorRef").stringValue()).isEqualTo(references.personReference(author.value))
            assertThat(body.has("authorName")).isFalse()
            assertThat(body.has("authorEmail")).isFalse()
            assertThat(body.toString()).doesNotContain("Jan Jansen").doesNotContain("voorbeeld")
            val withoutAuthor = support.requests.single { it.path.endsWith("/messages/${anonymous.id.value}") }.json()
            assertThat(withoutAuthor.path("authorName").stringValue()).isEqualTo(RestSupportRequestSyncAdapter.ANONYMOUS_AUTHOR)
            assertThat(withoutAuthor.has("authorRef")).isFalse()
        } finally {
            jdbi.useHandle<Exception> { it.createUpdate("DELETE FROM app_metadata WHERE key = :key").bind("key", SupportPrivacy.KEY).execute() }
            privacy.refresh()
        }
    }

    @Test
    fun `text over the protocol's limits is cut to fit, so the service never refuses it`() {
        val long = supportRequest(sourceUrl = "https://suite.example.org/tenants/acme-fb/templates?q=" + "u".repeat(5_000))
            .copy(title = "T".repeat(1_500), description = "é".repeat(200_000))
        val comment = SupportRequestComment(
            id = SupportRequestCommentKey.generate(),
            requestId = long.id,
            tenantKey = TENANT,
            body = "b".repeat(300 * 1024),
            authorName = "A".repeat(250),
            authorEmail = "jan@voorbeeld.nl",
            source = CommentSource.LOCAL,
            externalCommentId = null,
            createdAt = CREATED_AT,
        )

        port.createTicket(long)
        port.addComment(synced(), comment)

        val ticket = support.requests.last { it.path == "/api/v2/requests/acme-fb/${long.id.value}" }.json()
        assertThat(ticket.path("title").stringValue()).hasSize(RestSupportRequestSyncAdapter.MAX_TITLE_CHARS).startsWith("TTT").endsWith("…")
        val question = support.requests.last { it.path.startsWith("/api/v2/requests/0193-fb/messages/") && it.json().path("kind").stringValue() == "QUESTION" }.json()
        assertThat(question.path("body").stringValue().toByteArray().size).isLessThanOrEqualTo(RestSupportRequestSyncAdapter.MAX_TEXT_BYTES)
        assertThat(question.path("body").stringValue()).startsWith("ééé").endsWith(RestSupportRequestSyncAdapter.CUT_MARKER)
        assertThat(ticket.path("sourceUrl").stringValue()).hasSizeLessThanOrEqualTo(RestSupportRequestSyncAdapter.MAX_SOURCE_URL_CHARS)
            .startsWith("https://suite.example.org/tenants/acme-fb/templates?q=uuu")
        val sentComment = support.requests.last { it.path.endsWith("/messages/${comment.id.value}") }.json()
        assertThat(sentComment.path("body").stringValue().toByteArray().size).isLessThanOrEqualTo(RestSupportRequestSyncAdapter.MAX_TEXT_BYTES)
        assertThat(sentComment.path("authorName").stringValue()).hasSize(RestSupportRequestSyncAdapter.MAX_AUTHOR_NAME_CHARS)
    }

    @Test
    fun `changes are fetched with the acknowledgement, and what this suite does not understand is skipped`() {
        support.route("POST", "/api/v2/requests/changes") { r ->
            scoped(r) {
                FakeSupportResponse.json(
                    200,
                    """
                    {
                      "changes": [
                        {"sequence": 8, "requestId": "0193-fb", "tenantKey": "acme-fb", "suiteRequestId": "x", "occurredAt": "2026-11-05T14:00:00Z",
                         "type": "MESSAGE", "message": {"messageId": "0193-c2", "kind": "REPLY", "authorName": "Epistola support", "authorEmail": null, "body": "Fixed in 2.0.1"}},
                        {"sequence": 9, "requestId": "0193-fb", "tenantKey": "acme-fb", "suiteRequestId": "x", "occurredAt": "2026-11-05T14:01:00Z",
                         "type": "STATUS", "status": "RESOLVED"},
                        {"sequence": 10, "requestId": "0193-fb", "tenantKey": "acme-fb", "occurredAt": "2026-11-05T14:02:00Z", "type": "LABEL"},
                        {"sequence": 11, "requestId": "0193-fb", "tenantKey": "acme-fb", "occurredAt": "2026-11-05T14:03:00Z", "type": "STATUS", "status": "ARCHIVED"},
                        {"sequence": 12, "requestId": "0193-fb", "tenantKey": "acme-fb", "occurredAt": "2026-11-05T14:04:00Z", "type": "PRIORITY", "priority": "LOW"}
                      ],
                      "hasMore": true,
                      "lastSequence": 12
                    }
                    """.trimIndent(),
                )
            }
        }

        val page = port.fetchUpdates(7)

        val request = support.requests.last { it.path == "/api/v2/requests/changes" }
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.json().path("acknowledgeUpTo").asLong()).isEqualTo(7)
        assertThat(request.json().path("limit").asInt()).isEqualTo(100)
        assertThat(page.lastSequence).isEqualTo(12)
        assertThat(page.hasMore).isTrue()
        assertThat(page.updates).hasSize(3)
        val comment = page.updates[0] as ExternalUpdate.Comment
        assertThat(comment.seq).isEqualTo(8)
        assertThat(comment.tenantKey).isEqualTo(TENANT)
        assertThat(comment.externalRef).isEqualTo("0193-fb")
        assertThat(comment.externalCommentId).isEqualTo("0193-c2")
        assertThat(comment.authorName).isEqualTo("Epistola support")
        assertThat(comment.authorEmail).isNull()
        assertThat(comment.body).isEqualTo("Fixed in 2.0.1")
        val status = page.updates[1] as ExternalUpdate.StatusChange
        assertThat(status.newStatus).isEqualTo(SupportRequestStatus.RESOLVED)
        assertThat(status.seq).isEqualTo(9)
        val priority = page.updates[2] as ExternalUpdate.PriorityChange
        assertThat(priority.newPriority).isEqualTo(SupportRequestPriority.LOW)
    }

    @Test
    fun `an unavailable or overloaded service means later, a refused item does not`() {
        support.route("PUT", "/api/v2/requests/0193-down/") { FakeSupportResponse.json(503, """{"error":"unavailable"}""") }
        support.route("PUT", "/api/v2/requests/0193-busy/") { FakeSupportResponse.json(429, """{"error":"slow_down"}""") }
        support.route("PUT", "/api/v2/requests/0193-bad/") { FakeSupportResponse.json(400, """{"error":"invalid_request"}""") }

        val down = catchThrowable { port.updateStatus(synced("0193-down"), SupportRequestStatus.OPEN) }
        val busy = catchThrowable { port.updateStatus(synced("0193-busy"), SupportRequestStatus.OPEN) }
        val bad = catchThrowable { port.updateStatus(synced("0193-bad"), SupportRequestStatus.OPEN) }

        assertThat(down.isSupportUnavailable()).isTrue()
        assertThat(down.isSupportRequestSyncDeferred()).isTrue()
        assertThat(busy.isSupportUnavailable()).isTrue()
        assertThat(busy.isSupportRequestSyncDeferred()).isTrue()
        assertThat(bad).isInstanceOf(HttpClientErrorException::class.java)
        assertThat(bad.isSupportRequestSyncDeferred()).isFalse()
    }

    @Test
    fun `a quota the service enforces is a failure of the item, not a later`() {
        support.route("PUT", "/api/v2/requests/0193-full/") { FakeSupportResponse.json(507, """{"error":"quota_exceeded","error_description":"10 attachments per support request"}""") }

        val full = catchThrowable { port.updateStatus(synced("0193-full"), SupportRequestStatus.OPEN) }

        assertThat(full).isInstanceOf(HttpServerErrorException::class.java)
        assertThat(full.isSupportUnavailable()).isFalse()
        assertThat(full.isSupportRequestSyncDeferred()).`as`("counted against the item, not retried every round for ever").isFalse()
    }

    @Test
    fun `a token without the support request scope means not ready, without counting an attempt`() {
        support.activate(scopes = setOf("telemetry"))
        support.expireTokens()

        val failure = catchThrowable { port.createTicket(supportRequest()) }

        assertThat(failure).isInstanceOf(SupportRequestSyncNotReadyException::class.java)
        assertThat(failure.isSupportRequestSyncDeferred()).isTrue()
    }

    @Test
    fun `a support request scope Epistola withdraws stops support request at once, until a check finds it granted again`() {
        assertThat(port.isReady()).isTrue()
        // Staff withdraw requests: the service refuses it at once, with the token still valid.
        support.activate(scopes = setOf("telemetry"))

        val failure = catchThrowable { port.createTicket(supportRequest()) }

        assertThat(failure.isSupportRequestSyncDeferred()).`as`("kept for later, no attempt counted").isTrue()
        assertThat(port.isReady()).`as`("the drivers stop asking: no request per round until the next check").isFalse()

        support.activate(scopes = setOf("requests"))
        connection.checkNow()
        assertThat(port.isReady()).isTrue()
        assertThat(port.createTicket(supportRequest()).externalRef).isEqualTo("0193-fb")
    }

    @Test
    fun `a 401 is retried once with a fresh token, and one that stays means not ready`() {
        support.route("PUT", "/api/v2/requests/0193-gone/") { FakeSupportResponse.invalidToken() }

        val failure = catchThrowable { port.updateStatus(synced("0193-gone"), SupportRequestStatus.OPEN) }

        assertThat(failure).isInstanceOf(SupportRequestSyncNotReadyException::class.java)
        val tries = support.requests.filter { it.path.startsWith("/api/v2/requests/0193-gone/messages/") }
        assertThat(tries).hasSize(2)
        assertThat(tries.map { it.bearerToken }.distinct()).`as`("the retry carries a new token").hasSize(2)
    }

    private fun scoped(request: FakeSupportRequest, answer: () -> FakeSupportResponse): FakeSupportResponse = when {
        request.clientId == null -> FakeSupportResponse.invalidToken()
        "requests" !in request.tokenScopes -> FakeSupportResponse.insufficientScope("requests")
        else -> answer()
    }

    private fun supportRequest(
        sourceUrl: String? = "https://suite.example.org/tenants/acme-fb/templates?q=letter",
        consoleLogs: String? = null,
        metadata: String? = null,
        serverLogs: String? = null,
    ) = SupportRequest(
        id = SupportRequestKey.generate(),
        tenantKey = TENANT,
        title = "Preview does not load",
        description = "It spins",
        category = SupportRequestCategory.BUG,
        status = SupportRequestStatus.OPEN,
        priority = SupportRequestPriority.HIGH,
        sourceUrl = sourceUrl,
        consoleLogs = consoleLogs,
        metadata = metadata,
        createdBy = UserKey.of("00000000-0000-0000-0000-feedbac00093"),
        createdAt = CREATED_AT,
        updatedAt = CREATED_AT,
        externalRef = null,
        externalUrl = null,
        syncStatus = SyncStatus.PENDING,
        serverLogs = serverLogs,
    )

    private fun synced(externalRef: String = "0193-fb") = supportRequest().copy(externalRef = externalRef, syncStatus = SyncStatus.SYNCED)

    companion object {
        private val TENANT = TenantKey.of("acme-fb")
        private val CREATED_AT: OffsetDateTime = OffsetDateTime.of(2026, 11, 5, 14, 59, 0, 0, ZoneOffset.ofHours(1))

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
        }

        @JvmStatic
        @AfterAll
        fun stop() = support.close()
    }
}

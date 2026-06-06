package app.epistola.suite.support.feedback

import app.epistola.hub.client.EpistolaHubClient
import app.epistola.hub.client.port.InstallationCredentials
import app.epistola.hub.client.port.InstallationStore
import app.epistola.hub.proto.v1.AddFeedbackCommentRequest
import app.epistola.hub.proto.v1.AddFeedbackCommentResponse
import app.epistola.hub.proto.v1.FeedbackUpdate
import app.epistola.hub.proto.v1.FetchFeedbackUpdatesRequest
import app.epistola.hub.proto.v1.FetchFeedbackUpdatesResponse
import app.epistola.hub.proto.v1.SubmitFeedbackRequest
import app.epistola.hub.proto.v1.SubmitFeedbackResponse
import app.epistola.suite.common.ids.FeedbackKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.feedback.Feedback
import app.epistola.suite.feedback.FeedbackCategory
import app.epistola.suite.feedback.FeedbackPriority
import app.epistola.suite.feedback.FeedbackStatus
import app.epistola.suite.feedback.SyncStatus
import app.epistola.suite.feedback.sync.ExternalUpdate
import com.google.protobuf.Timestamp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import app.epistola.hub.proto.v1.FeedbackStatus as ProtoStatus

class HubFeedbackSyncAdapterTest {
    private val noopStore =
        object : InstallationStore {
            override fun load(): InstallationCredentials? = null

            override fun save(credentials: InstallationCredentials) = Unit
        }

    private fun feedback(externalRef: String? = null) = Feedback(
        id = FeedbackKey.generate(),
        tenantKey = TenantKey.of("acme"),
        title = "Login broken",
        description = "Cannot log in",
        category = FeedbackCategory.BUG,
        status = FeedbackStatus.OPEN,
        priority = FeedbackPriority.HIGH,
        sourceUrl = "https://app/login",
        consoleLogs = null,
        metadata = """{"browser":"Safari"}""",
        createdBy = UserKey.of("00000000-0000-0000-0000-000000000001"),
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now(),
        externalRef = externalRef,
        externalUrl = null,
        syncStatus = SyncStatus.PENDING,
        syncAttempts = 0,
    )

    @Test
    fun `createTicket maps feedback to a SubmitFeedback request and returns the hub ref`() {
        var captured: SubmitFeedbackRequest? = null
        val client =
            object : EpistolaHubClient(store = noopStore) {
                override fun submitFeedback(request: SubmitFeedbackRequest): SubmitFeedbackResponse {
                    captured = request
                    return SubmitFeedbackResponse.newBuilder().setFeedbackId("hub-123").setUrl("https://hub/f/hub-123").build()
                }
            }
        val fb = feedback()

        val result = HubFeedbackSyncAdapter(client).createTicket(fb, emptyList())

        assertThat(result.externalRef).isEqualTo("hub-123")
        assertThat(result.externalUrl).isEqualTo("https://hub/f/hub-123")
        assertThat(captured!!.tenant).isEqualTo("acme")
        assertThat(captured!!.suiteFeedbackId).isEqualTo(fb.id.value.toString())
        assertThat(captured!!.title).isEqualTo("Login broken")
        assertThat(captured!!.category).isEqualTo(app.epistola.hub.proto.v1.FeedbackCategory.FEEDBACK_CATEGORY_BUG)
        assertThat(captured!!.priority).isEqualTo(app.epistola.hub.proto.v1.FeedbackPriority.FEEDBACK_PRIORITY_HIGH)
    }

    @Test
    fun `addComment targets the hub external ref`() {
        var captured: AddFeedbackCommentRequest? = null
        val client =
            object : EpistolaHubClient(store = noopStore) {
                override fun addFeedbackComment(request: AddFeedbackCommentRequest): AddFeedbackCommentResponse {
                    captured = request
                    return AddFeedbackCommentResponse.newBuilder().setCommentId("c-1").build()
                }
            }
        val fb = feedback(externalRef = "hub-123")
        val comment =
            app.epistola.suite.feedback.FeedbackComment(
                id = app.epistola.suite.common.ids.FeedbackCommentKey.generate(),
                feedbackId = fb.id,
                tenantKey = fb.tenantKey,
                body = "Any update?",
                authorName = "Alice",
                authorEmail = "alice@acme.test",
                source = app.epistola.suite.feedback.CommentSource.LOCAL,
                externalCommentId = null,
                createdAt = OffsetDateTime.now(),
            )

        val ref = HubFeedbackSyncAdapter(client).addComment(fb, comment)

        assertThat(ref.externalCommentId).isEqualTo("c-1")
        assertThat(captured!!.feedbackId).isEqualTo("hub-123")
        assertThat(captured!!.body).isEqualTo("Any update?")
        assertThat(captured!!.authorName).isEqualTo("Alice")
    }

    @Test
    fun `fetchUpdates converts status changes and comments to ExternalUpdate`() {
        val ts = Timestamp.newBuilder().setSeconds(1_700_000_000).build()
        val statusUpdate =
            FeedbackUpdate
                .newBuilder()
                .setFeedbackId("hub-1")
                .setTenant("acme")
                .setOccurredAt(ts)
                .setStatusChange(
                    FeedbackUpdate.StatusChange.newBuilder().setStatus(ProtoStatus.FEEDBACK_STATUS_RESOLVED),
                ).build()
        val commentUpdate =
            FeedbackUpdate
                .newBuilder()
                .setFeedbackId("hub-2")
                .setTenant("beta")
                .setOccurredAt(ts)
                .setComment(
                    FeedbackUpdate.CommentAdded
                        .newBuilder()
                        .setExternalCommentId("hc-9")
                        .setAuthorName("Triager")
                        .setBody("Looking into it"),
                ).build()
        val client =
            object : EpistolaHubClient(store = noopStore) {
                override fun fetchFeedbackUpdates(request: FetchFeedbackUpdatesRequest): FetchFeedbackUpdatesResponse = FetchFeedbackUpdatesResponse.newBuilder().addUpdates(statusUpdate).addUpdates(commentUpdate).build()
            }

        val updates = HubFeedbackSyncAdapter(client).fetchUpdates(Instant.EPOCH)

        assertThat(updates).hasSize(2)
        val status = updates.filterIsInstance<ExternalUpdate.StatusChange>().single()
        assertThat(status.tenantKey).isEqualTo(TenantKey.of("acme"))
        assertThat(status.externalRef).isEqualTo("hub-1")
        assertThat(status.newStatus).isEqualTo(FeedbackStatus.RESOLVED)
        val comment = updates.filterIsInstance<ExternalUpdate.Comment>().single()
        assertThat(comment.tenantKey).isEqualTo(TenantKey.of("beta"))
        assertThat(comment.externalCommentId).isEqualTo("hc-9")
        assertThat(comment.authorName).isEqualTo("Triager")
    }

    @Test
    fun `isEnabled is true`() {
        val client = object : EpistolaHubClient(store = noopStore) {}
        assertThat(HubFeedbackSyncAdapter(client).isEnabled()).isTrue()
    }
}

package app.epistola.suite.feedback

import app.epistola.suite.common.ids.FeedbackCommentId
import app.epistola.suite.common.ids.FeedbackCommentKey
import app.epistola.suite.common.ids.FeedbackId
import app.epistola.suite.common.ids.FeedbackKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.feedback.commands.AddFeedbackComment
import app.epistola.suite.feedback.commands.CreateFeedback
import app.epistola.suite.feedback.commands.SyncFeedbackComment
import app.epistola.suite.feedback.commands.SyncFeedbackStatus
import app.epistola.suite.feedback.commands.UpdateFeedbackStatus
import app.epistola.suite.feedback.queries.GetFeedback
import app.epistola.suite.feedback.queries.GetFeedbackComments
import app.epistola.suite.feedback.queries.ListPendingSyncFeedback
import app.epistola.suite.feedback.sync.ExternalUpdate
import app.epistola.suite.feedback.sync.ExternalUpdatePage
import app.epistola.suite.feedback.sync.FeedbackPollScheduler
import app.epistola.suite.feedback.sync.FeedbackSyncPort
import app.epistola.suite.feedback.sync.FeedbackSyncScheduler
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.metadata.getAs
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.IntegrationTestBase
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import java.time.Instant

/**
 * Exercises the feedback sync machinery (event-handler push path, poll-back, retry sweep)
 * against a [RecordingFeedbackSyncPort] standing in for the hub adapter. The push handlers run
 * in the IMMEDIATE phase, so executing a command through the mediator drives them directly.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(FeedbackSyncDriverIntegrationTest.TestConfig::class)
@TestPropertySource(
    properties = [
        "epistola.feedback.sync.enabled=true",
        "epistola.feedback.sync.retry-interval-ms=3600000",
        "epistola.feedback.sync.polling.enabled=true",
        "epistola.feedback.sync.polling.interval-ms=3600000",
    ],
)
class FeedbackSyncDriverIntegrationTest : IntegrationTestBase() {

    class TestConfig {
        @Bean
        @Primary
        fun recordingFeedbackSyncPort(): FeedbackSyncPort = RecordingFeedbackSyncPort()

        // The retry scheduler records sync-outcome metrics; the minimal TestApplication has no
        // actuator MeterRegistry, so provide a simple one (the real app gets it from actuator).
        @Bean
        @ConditionalOnMissingBean(MeterRegistry::class)
        fun testMeterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }

    @Autowired
    lateinit var syncPort: FeedbackSyncPort

    @Autowired
    lateinit var pollScheduler: FeedbackPollScheduler

    @Autowired
    lateinit var retryScheduler: FeedbackSyncScheduler

    @Autowired
    lateinit var appMetadata: AppMetadataService

    private val recording get() = syncPort as RecordingFeedbackSyncPort

    private val testUserKey = UserKey.of("00000000-0000-0000-0000-feedbac00077")

    @BeforeEach
    fun setUp() {
        recording.reset()
        appMetadata.setAs(FeedbackPollScheduler.CURSOR_KEY, FeedbackPollScheduler.Cursor(0))
        ensureUser(testUserKey, "feedback-driver-author", "feedback-driver@epistola.test", "Feedback Driver Author")
    }

    private fun createSyncedFeedback(tenant: Tenant, title: String = "Driver Bug"): Feedback = withMediator {
        val created = CreateFeedback(
            id = FeedbackId(FeedbackKey.generate(), TenantId(tenant.id)),
            title = title,
            description = "desc",
            category = FeedbackCategory.BUG,
            priority = FeedbackPriority.MEDIUM,
            sourceUrl = null,
            consoleLogs = null,
            metadata = null,
            createdBy = testUserKey,
        ).execute()
        // OnFeedbackCreated has already pushed and stored the ref; reload the synced row.
        GetFeedback(FeedbackId(created.id, TenantId(tenant.id))).query()!!
    }

    @Test
    fun `creating feedback pushes to the hub and stores the external ref`() {
        val tenant = createTenant("Driver Push")
        val feedback = createSyncedFeedback(tenant)

        assertThat(recording.createdTickets.map { it.id }).contains(feedback.id)
        assertThat(feedback.syncStatus).isEqualTo(SyncStatus.SYNCED)
        assertThat(feedback.externalRef).isEqualTo("hub-${feedback.id.value}")
    }

    @Test
    fun `adding a local comment pushes it and stores the external comment id`() {
        val tenant = createTenant("Driver Comment")
        val feedback = createSyncedFeedback(tenant)
        recording.addedComments.clear()

        withMediator {
            AddFeedbackComment(
                id = FeedbackCommentId(FeedbackCommentKey.generate(), FeedbackId(feedback.id, TenantId(tenant.id))),
                body = "Please fix",
                authorName = "Alice",
                authorEmail = "alice@acme.test",
            ).execute()
        }

        assertThat(recording.addedComments).hasSize(1)
        val stored = withMediator { GetFeedbackComments(FeedbackId(feedback.id, TenantId(tenant.id))).query() }
        assertThat(stored.single().externalCommentId).isNotNull()
    }

    @Test
    fun `an inbound synced comment is not pushed back out`() {
        val tenant = createTenant("Driver NoLoop")
        val feedback = createSyncedFeedback(tenant)
        recording.addedComments.clear()

        withMediator {
            SyncFeedbackComment(
                tenantKey = tenant.id,
                feedbackId = feedback.id,
                body = "from the hub",
                authorName = "Triager",
                authorEmail = null,
                externalCommentId = "ext-inbound-1",
            ).execute()
        }

        assertThat(recording.addedComments).isEmpty()
    }

    @Test
    fun `a local status change is pushed to the hub`() {
        val tenant = createTenant("Driver Status")
        val feedback = createSyncedFeedback(tenant)

        withMediator {
            UpdateFeedbackStatus(
                id = FeedbackId(feedback.id, TenantId(tenant.id)),
                status = FeedbackStatus.RESOLVED,
            ).execute()
        }

        assertThat(recording.statusUpdates.map { it.second }).contains(FeedbackStatus.RESOLVED)
    }

    @Test
    fun `an inbound synced status change is not pushed back out`() {
        val tenant = createTenant("Driver StatusNoLoop")
        val feedback = createSyncedFeedback(tenant)
        recording.statusUpdates.clear()

        withMediator {
            SyncFeedbackStatus(
                id = FeedbackId(feedback.id, TenantId(tenant.id)),
                status = FeedbackStatus.CLOSED,
            ).execute()
        }

        assertThat(recording.statusUpdates).isEmpty()
    }

    @Test
    fun `polling applies inbound status and comment and advances the cursor`() {
        val tenant = createTenant("Driver Poll")
        val feedback = createSyncedFeedback(tenant)
        val ref = feedback.externalRef!!

        recording.pages.addLast(
            ExternalUpdatePage(
                updates = listOf(
                    ExternalUpdate.StatusChange(
                        seq = 4,
                        tenantKey = tenant.id,
                        externalRef = ref,
                        occurredAt = Instant.now(),
                        newStatus = FeedbackStatus.IN_PROGRESS,
                    ),
                ),
                nextSeq = 4,
                hasMore = true,
            ),
        )
        recording.pages.addLast(
            ExternalUpdatePage(
                updates = listOf(
                    ExternalUpdate.Comment(
                        seq = 9,
                        tenantKey = tenant.id,
                        externalRef = ref,
                        occurredAt = Instant.now(),
                        externalCommentId = "hub-c-1",
                        authorName = "Triager",
                        authorEmail = null,
                        body = "On it",
                    ),
                ),
                nextSeq = 9,
                hasMore = false,
            ),
        )

        pollScheduler.pollForUpdates()

        // Both pages drained in one run, oldest cursor first.
        assertThat(recording.fetchCalls).containsExactly(0L, 4L)
        val updated = withMediator { GetFeedback(FeedbackId(feedback.id, TenantId(tenant.id))).query()!! }
        assertThat(updated.status).isEqualTo(FeedbackStatus.IN_PROGRESS)
        val comments = withMediator { GetFeedbackComments(FeedbackId(feedback.id, TenantId(tenant.id))).query() }
        assertThat(comments.map { it.externalCommentId }).contains("hub-c-1")
        assertThat(appMetadata.getAs<FeedbackPollScheduler.Cursor>(FeedbackPollScheduler.CURSOR_KEY)!!.seq).isEqualTo(9)
    }

    @Test
    fun `an empty poll leaves the cursor unchanged`() {
        appMetadata.setAs(FeedbackPollScheduler.CURSOR_KEY, FeedbackPollScheduler.Cursor(42))

        pollScheduler.pollForUpdates()

        assertThat(appMetadata.getAs<FeedbackPollScheduler.Cursor>(FeedbackPollScheduler.CURSOR_KEY)!!.seq).isEqualTo(42)
    }

    @Test
    fun `retry marks feedback FAILED after exhausting attempts`() {
        val tenant = createTenant("Driver Retry")
        recording.failCreate = true

        // Create a PENDING item whose immediate push failed (failCreate swallowed in OnFeedbackCreated).
        val feedbackId = FeedbackId(FeedbackKey.generate(), TenantId(tenant.id))
        withMediator {
            CreateFeedback(
                id = feedbackId,
                title = "Will fail",
                description = "desc",
                category = FeedbackCategory.BUG,
                priority = FeedbackPriority.LOW,
                sourceUrl = null,
                consoleLogs = null,
                metadata = null,
                createdBy = testUserKey,
            ).execute()
        }
        assertThat(withMediator { GetFeedback(feedbackId).query()!! }.syncStatus).isEqualTo(SyncStatus.PENDING)

        // Each retry sweep increments attempts; after MAX it flips to FAILED.
        repeat(ListPendingSyncFeedback.MAX_SYNC_ATTEMPTS) { retryScheduler.retryPendingSync() }

        assertThat(withMediator { GetFeedback(feedbackId).query()!! }.syncStatus).isEqualTo(SyncStatus.FAILED)
    }

    @Test
    fun `retry re-pushes a comment whose immediate push failed`() {
        val tenant = createTenant("Driver CommentRetry")
        val feedback = createSyncedFeedback(tenant)

        // Immediate comment push fails, leaving external_comment_id null.
        recording.failAddComment = true
        val commentId = FeedbackCommentId(FeedbackCommentKey.generate(), FeedbackId(feedback.id, TenantId(tenant.id)))
        withMediator {
            AddFeedbackComment(
                id = commentId,
                body = "retry me",
                authorName = "Bob",
                authorEmail = null,
            ).execute()
        }
        assertThat(withMediator { GetFeedbackComments(FeedbackId(feedback.id, TenantId(tenant.id))).query() }.single().externalCommentId).isNull()

        // Hub recovers; the retry sweep pushes it and stores the ref.
        recording.failAddComment = false
        recording.addedComments.clear()
        retryScheduler.retryPendingSync()

        assertThat(recording.addedComments).hasSize(1)
        assertThat(withMediator { GetFeedbackComments(FeedbackId(feedback.id, TenantId(tenant.id))).query() }.single().externalCommentId).isNotNull()
    }
}

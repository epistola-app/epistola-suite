package app.epistola.suite.feedback

import app.epistola.suite.common.ids.FeedbackCommentId
import app.epistola.suite.common.ids.FeedbackCommentKey
import app.epistola.suite.common.ids.FeedbackId
import app.epistola.suite.common.ids.FeedbackKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.feedback.commands.AddFeedbackComment
import app.epistola.suite.feedback.commands.CreateFeedback
import app.epistola.suite.feedback.commands.UpdateFeedbackStatus
import app.epistola.suite.feedback.queries.GetFeedback
import app.epistola.suite.feedback.queries.GetFeedbackComments
import app.epistola.suite.feedback.queries.ListFeedback
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The core feedback lifecycle — create (with the default no-op sync port), move through the status
 * flow, comment, and read back — exercised the way production does: commands and queries through
 * the mediator.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeedbackLifecycleIntegrationTest : IntegrationTestBase() {

    // Feedback is authored by a dedicated user (feedback.created_by is a mandatory FK to users).
    private val authorKey = UserKey.of("00000000-0000-0000-0000-feedbac00199")

    @BeforeEach
    fun ensureFeedbackAuthor() {
        ensureUser(authorKey, "feedback-lifecycle-author", "feedback-lifecycle-author@epistola.test", "Feedback Lifecycle Author")
    }

    private fun createFeedback(
        tenant: Tenant,
        title: String = "Lifecycle Bug",
        sourceUrl: String? = "https://app.example.com/tenants/acme/templates",
    ): Feedback = withMediator {
        CreateFeedback(
            id = FeedbackId(FeedbackKey.generate(), TenantId(tenant.id)),
            title = title,
            description = "Description for $title",
            category = FeedbackCategory.BUG,
            priority = FeedbackPriority.MEDIUM,
            sourceUrl = sourceUrl,
            consoleLogs = null,
            metadata = null,
            createdBy = authorKey,
        ).execute()
    }

    @Test
    fun `feedback walks the full status lifecycle`() {
        withMediator {
            val tenant = createTenant("Feedback Lifecycle")
            val created = createFeedback(tenant)
            val feedbackId = FeedbackId(created.id, TenantId(tenant.id))
            assertThat(created.status).isEqualTo(FeedbackStatus.OPEN)

            for (status in listOf(FeedbackStatus.IN_PROGRESS, FeedbackStatus.RESOLVED, FeedbackStatus.CLOSED)) {
                val updated = UpdateFeedbackStatus(id = feedbackId, status = status).execute()
                assertThat(updated).isTrue()
                assertThat(GetFeedback(feedbackId).query()!!.status).isEqualTo(status)
            }

            // A status change never touches the sync fields: with the no-op port the item stays local.
            assertThat(GetFeedback(feedbackId).query()!!.syncStatus).isEqualTo(SyncStatus.NOT_CONFIGURED)
        }
    }

    @Test
    fun `UpdateFeedbackStatus returns false for unknown feedback`() {
        withMediator {
            val tenant = createTenant("Feedback Status Missing")
            val missingId = FeedbackId(FeedbackKey.generate(), TenantId(tenant.id))

            val updated = UpdateFeedbackStatus(id = missingId, status = FeedbackStatus.RESOLVED).execute()

            assertThat(updated).isFalse()
        }
    }

    @Test
    fun `UpdateFeedbackStatus is tenant-scoped`() {
        withMediator {
            val owner = createTenant("Feedback Status Owner")
            val other = createTenant("Feedback Status Other")
            val created = createFeedback(owner)

            val crossTenantId = FeedbackId(created.id, TenantId(other.id))
            val updated = UpdateFeedbackStatus(id = crossTenantId, status = FeedbackStatus.CLOSED).execute()

            assertThat(updated).isFalse()
            val unchanged = GetFeedback(FeedbackId(created.id, TenantId(owner.id))).query()!!
            assertThat(unchanged.status).isEqualTo(FeedbackStatus.OPEN)
        }
    }

    @Test
    fun `comments are returned in creation order`() {
        withMediator {
            val tenant = createTenant("Feedback Comment Order")
            val created = createFeedback(tenant)
            val feedbackId = FeedbackId(created.id, TenantId(tenant.id))

            for (body in listOf("first", "second", "third")) {
                AddFeedbackComment(
                    id = FeedbackCommentId(FeedbackCommentKey.generate(), feedbackId),
                    body = body,
                    authorName = "Commenter",
                    authorEmail = null,
                ).execute()
            }

            val comments = GetFeedbackComments(feedbackId).query()
            assertThat(comments).extracting<String> { it.body }.containsExactly("first", "second", "third")
            assertThat(comments).allMatch { it.source == CommentSource.LOCAL }

            // The list view rolls the comments up into the summary count.
            val summary = ListFeedback(tenantKey = tenant.id).query().single { it.id == created.id }
            assertThat(summary.commentCount).isEqualTo(3)
        }
    }

    @Test
    fun `ListFeedback filters open items by source page path`() {
        withMediator {
            val tenant = createTenant("Feedback Source Filter")
            val onPage = createFeedback(tenant, "On the templates page")
            val withQuery = createFeedback(
                tenant,
                "Same page with query string",
                sourceUrl = "https://app.example.com/tenants/acme/templates?tab=drafts",
            )
            createFeedback(tenant, "Different page", sourceUrl = "https://app.example.com/tenants/acme/themes")
            val resolved = createFeedback(tenant, "Resolved on the templates page")
            UpdateFeedbackStatus(
                id = FeedbackId(resolved.id, TenantId(tenant.id)),
                status = FeedbackStatus.RESOLVED,
            ).execute()

            val items = ListFeedback(tenantKey = tenant.id, sourceUrl = "/tenants/acme/templates").query()

            // Path matching includes the query-string variant; the resolved item and the other page are hidden.
            assertThat(items.map { it.id }).containsExactlyInAnyOrder(onPage.id, withQuery.id)
        }
    }
}

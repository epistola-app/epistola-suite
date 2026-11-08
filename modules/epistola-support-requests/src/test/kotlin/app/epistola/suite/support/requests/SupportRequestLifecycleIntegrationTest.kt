// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests

import app.epistola.suite.common.ids.SupportRequestCommentId
import app.epistola.suite.common.ids.SupportRequestCommentKey
import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.common.ids.SupportRequestKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.requests.commands.AddSupportRequestComment
import app.epistola.suite.support.requests.commands.CreateSupportRequest
import app.epistola.suite.support.requests.commands.SyncSupportRequestPriority
import app.epistola.suite.support.requests.commands.UpdateSupportRequestStatus
import app.epistola.suite.support.requests.desk.SetSupportDeskPriority
import app.epistola.suite.support.requests.queries.GetSupportRequest
import app.epistola.suite.support.requests.queries.GetSupportRequestComments
import app.epistola.suite.support.requests.queries.ListSupportRequest
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
class SupportRequestLifecycleIntegrationTest : IntegrationTestBase() {

    // Feedback is authored by a dedicated user (feedback.created_by is a mandatory FK to users).
    private val authorKey = UserKey.of("00000000-0000-0000-0000-feedbac00199")

    @BeforeEach
    fun ensureSupportRequestAuthor() {
        ensureUser(authorKey, "support-request-lifecycle-author", "support-request-lifecycle-author@epistola.test", "Feedback Lifecycle Author")
    }

    private fun createSupportRequest(
        tenant: Tenant,
        title: String = "Lifecycle Bug",
        sourceUrl: String? = "https://app.example.com/tenants/acme/templates",
    ): SupportRequest = withMediator {
        CreateSupportRequest(
            id = SupportRequestId(SupportRequestKey.generate(), TenantId(tenant.id)),
            title = title,
            description = "Description for $title",
            category = SupportRequestCategory.BUG,
            priority = SupportRequestPriority.MEDIUM,
            sourceUrl = sourceUrl,
            consoleLogs = null,
            metadata = null,
            createdBy = authorKey,
        ).execute()
    }

    @Test
    fun `support request walks the full status lifecycle`() {
        withMediator {
            val tenant = createTenant("Feedback Lifecycle")
            val created = createSupportRequest(tenant)
            val supportRequestId = SupportRequestId(created.id, TenantId(tenant.id))
            assertThat(created.status).isEqualTo(SupportRequestStatus.OPEN)

            for (status in listOf(SupportRequestStatus.IN_PROGRESS, SupportRequestStatus.RESOLVED, SupportRequestStatus.CLOSED)) {
                val updated = UpdateSupportRequestStatus(id = supportRequestId, status = status).execute()
                assertThat(updated).isTrue()
                assertThat(GetSupportRequest(supportRequestId).query()!!.status).isEqualTo(status)
            }

            // A status change never touches the sync fields: with the no-op port the item stays local.
            assertThat(GetSupportRequest(supportRequestId).query()!!.syncStatus).isEqualTo(SyncStatus.NOT_CONFIGURED)
        }
    }

    @Test
    fun `UpdateSupportRequestStatus returns false for unknown support request`() {
        withMediator {
            val tenant = createTenant("Feedback Status Missing")
            val missingId = SupportRequestId(SupportRequestKey.generate(), TenantId(tenant.id))

            val updated = UpdateSupportRequestStatus(id = missingId, status = SupportRequestStatus.RESOLVED).execute()

            assertThat(updated).isFalse()
        }
    }

    @Test
    fun `UpdateSupportRequestStatus is tenant-scoped`() {
        withMediator {
            val owner = createTenant("Feedback Status Owner")
            val other = createTenant("Feedback Status Other")
            val created = createSupportRequest(owner)

            val crossTenantId = SupportRequestId(created.id, TenantId(other.id))
            val updated = UpdateSupportRequestStatus(id = crossTenantId, status = SupportRequestStatus.CLOSED).execute()

            assertThat(updated).isFalse()
            val unchanged = GetSupportRequest(SupportRequestId(created.id, TenantId(owner.id))).query()!!
            assertThat(unchanged.status).isEqualTo(SupportRequestStatus.OPEN)
        }
    }

    @Test
    fun `comments are returned in creation order`() {
        withMediator {
            val tenant = createTenant("Feedback Comment Order")
            val created = createSupportRequest(tenant)
            val supportRequestId = SupportRequestId(created.id, TenantId(tenant.id))

            for (body in listOf("first", "second", "third")) {
                AddSupportRequestComment(
                    id = SupportRequestCommentId(SupportRequestCommentKey.generate(), supportRequestId),
                    body = body,
                    authorName = "Commenter",
                    authorEmail = null,
                ).execute()
            }

            val comments = GetSupportRequestComments(supportRequestId).query()
            assertThat(comments).extracting<String> { it.body }.containsExactly("first", "second", "third")
            assertThat(comments).allMatch { it.source == CommentSource.LOCAL }

            // The list view rolls the comments up into the summary count.
            val summary = ListSupportRequest(tenantKey = tenant.id).query().single { it.id == created.id }
            assertThat(summary.commentCount).isEqualTo(3)
        }
    }

    @Test
    fun `ListSupportRequest filters open items by source page path`() {
        withMediator {
            val tenant = createTenant("Feedback Source Filter")
            val onPage = createSupportRequest(tenant, "On the templates page")
            val withQuery = createSupportRequest(
                tenant,
                "Same page with query string",
                sourceUrl = "https://app.example.com/tenants/acme/templates?tab=drafts",
            )
            createSupportRequest(tenant, "Different page", sourceUrl = "https://app.example.com/tenants/acme/themes")
            val resolved = createSupportRequest(tenant, "Resolved on the templates page")
            UpdateSupportRequestStatus(
                id = SupportRequestId(resolved.id, TenantId(tenant.id)),
                status = SupportRequestStatus.RESOLVED,
            ).execute()

            val items = ListSupportRequest(tenantKey = tenant.id, sourceUrl = "/tenants/acme/templates").query()

            // Path matching includes the query-string variant; the resolved item and the other page are hidden.
            assertThat(items.map { it.id }).containsExactlyInAnyOrder(onPage.id, withQuery.id)
        }
    }

    @Test
    fun `the submitter's priority is the requested one, and the priority the handler sets leaves it as it was`() {
        val tenant = createTenant("Priority Lifecycle")
        val created = createSupportRequest(tenant)
        val id = SupportRequestId(created.id, TenantId(tenant.id))
        assertThat(created.requestedPriority).isEqualTo(SupportRequestPriority.MEDIUM)
        assertThat(created.priority).`as`("the priority starts as the requested one").isEqualTo(SupportRequestPriority.MEDIUM)

        // The inbox handles it: the desk sets the priority, and a connection's is not applied.
        assertThat(withMediator { SyncSupportRequestPriority(id, SupportRequestPriority.LOW).execute() }).`as`("not from a connection").isFalse()
        val changed = withMediator { SetSupportDeskPriority(tenant.id, created.id, SupportRequestPriority.HIGH).execute() }
        val again = withMediator { SetSupportDeskPriority(tenant.id, created.id, SupportRequestPriority.HIGH).execute() }

        assertThat(changed).isTrue()
        assertThat(again).`as`("an unchanged priority writes nothing").isFalse()
        val stored = withMediator { GetSupportRequest(id).query() }!!
        assertThat(stored.priority).isEqualTo(SupportRequestPriority.HIGH)
        assertThat(stored.requestedPriority).isEqualTo(SupportRequestPriority.MEDIUM)
    }
}

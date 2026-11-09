// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests

import app.epistola.suite.common.ids.SupportRequestAssetId
import app.epistola.suite.common.ids.SupportRequestAssetKey
import app.epistola.suite.common.ids.SupportRequestCommentId
import app.epistola.suite.common.ids.SupportRequestCommentKey
import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.common.ids.SupportRequestKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.requests.commands.AddSupportRequestAsset
import app.epistola.suite.support.requests.commands.AddSupportRequestComment
import app.epistola.suite.support.requests.commands.CreateSupportRequest
import app.epistola.suite.support.requests.commands.SyncSupportRequestComment
import app.epistola.suite.support.requests.commands.UpdateSupportRequestStatus
import app.epistola.suite.support.requests.commands.UpdateSupportRequestSyncRef
import app.epistola.suite.support.requests.commands.UpdateSupportRequestSyncStatus
import app.epistola.suite.support.requests.queries.GetSupportRequest
import app.epistola.suite.support.requests.queries.GetSupportRequestAssetContent
import app.epistola.suite.support.requests.queries.GetSupportRequestByExternalRef
import app.epistola.suite.support.requests.queries.GetSupportRequestComments
import app.epistola.suite.support.requests.queries.ListPendingSyncSupportRequest
import app.epistola.suite.support.requests.queries.ListSupportRequest
import app.epistola.suite.support.requests.queries.ListSupportRequestAssets
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SupportRequestIntegrationTest : IntegrationTestBase() {

    // Feedback is authored by a dedicated user (feedback.created_by is mandatory
    // NOT NULL). Materialise it through the harness — same EnsureUser command
    // production uses — instead of hand-written INSERT SQL.
    private val testUserKey = UserKey.of("00000000-0000-0000-0000-feedbac00099")

    @BeforeEach
    fun ensureSupportRequestAuthor() {
        ensureUser(testUserKey, "support-request-test-author", "support-request-test-author@epistola.test", "Feedback Test Author")
    }

    private fun createTestSupportRequest(tenant: Tenant, title: String = "Test Bug"): SupportRequest = withMediator {
        val supportRequestId = SupportRequestId(SupportRequestKey.generate(), TenantId(tenant.id))
        CreateSupportRequest(
            id = supportRequestId,
            title = title,
            description = "Description for $title",
            category = SupportRequestCategory.BUG,
            priority = SupportRequestPriority.MEDIUM,
            sourceUrl = "https://example.com/page",
            consoleLogs = null,
            metadata = null,
            createdBy = testUserKey,
        ).execute()
    }

    @Test
    fun `a colleague lists only their own support requests, a manager every one of the tenant`() {
        val tenant = createTenant("Requests Listed")
        fun viewer(n: Int) = app.epistola.suite.security.EpistolaPrincipal(
            userId = UserKey.of("00000000-0000-0000-0000-00000000c0${n}0"),
            externalId = "listed-viewer-$n",
            email = "viewer$n@listed.test",
            displayName = "Viewer $n",
            tenantMemberships = mapOf(tenant.id to setOf(app.epistola.suite.security.TenantRole.CONTENT_VIEWER)),
            currentTenantId = tenant.id,
        )
        val alice = viewer(1).also { ensureUser(it) }
        val bob = viewer(2).also { ensureUser(it) }
        for ((who, title) in listOf(alice to "Alice's salary letter", bob to "Bob's question")) {
            runAs(who) {
                CreateSupportRequest(
                    id = SupportRequestId(SupportRequestKey.generate(), TenantId(tenant.id)), title = title, description = "d",
                    category = SupportRequestCategory.BUG, priority = SupportRequestPriority.MEDIUM, sourceUrl = "https://example.com/p",
                    consoleLogs = null, metadata = null, createdBy = who.userId,
                ).execute()
            }
        }

        assertThat(runAs(alice) { ListSupportRequest(tenant.id).query() }.map { it.title }).containsExactly("Alice's salary letter")
        assertThat(runAs(bob) { ListSupportRequest(tenant.id, sourceUrl = "/p").query() }.map { it.title }).containsExactly("Bob's question")
        assertThat(withMediator { ListSupportRequest(tenant.id).query() }.map { it.title }).containsExactlyInAnyOrder("Alice's salary letter", "Bob's question")
    }

    @Nested
    inner class CreateSupportRequestTests {
        @Test
        fun `creates support request with correct fields`() {
            withMediator {
                val tenant = createTenant("Feedback Tenant")
                val supportRequestId = SupportRequestId(SupportRequestKey.generate(), TenantId(tenant.id))

                val supportRequest = CreateSupportRequest(
                    id = supportRequestId,
                    title = "Login fails on Safari",
                    description = "Steps to reproduce: open Safari, click login",
                    category = SupportRequestCategory.BUG,
                    priority = SupportRequestPriority.HIGH,
                    sourceUrl = "https://app.example.com/login",
                    consoleLogs = "[ERROR] auth failed",
                    metadata = """{"browser": "Safari"}""",
                    createdBy = testUserKey,
                ).execute()

                assertThat(supportRequest.id).isEqualTo(supportRequestId.key)
                assertThat(supportRequest.title).isEqualTo("Login fails on Safari")
                assertThat(supportRequest.category).isEqualTo(SupportRequestCategory.BUG)
                assertThat(supportRequest.priority).isEqualTo(SupportRequestPriority.HIGH)
                assertThat(supportRequest.status).isEqualTo(SupportRequestStatus.OPEN)
                assertThat(supportRequest.syncStatus).isEqualTo(SyncStatus.NOT_CONFIGURED)
                assertThat(supportRequest.syncAttempts).isEqualTo(0)
            }
        }

        @Test
        fun `rejects blank title`() {
            assertThatThrownBy {
                CreateSupportRequest(
                    id = SupportRequestId(SupportRequestKey.generate(), TenantId(TenantKey.of("any"))),
                    title = "  ",
                    description = "desc",
                    category = SupportRequestCategory.BUG,
                    priority = SupportRequestPriority.MEDIUM,
                    sourceUrl = null,
                    consoleLogs = null,
                    metadata = null,
                    createdBy = testUserKey,
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    inner class GetSupportRequestTests {
        @Test
        fun `retrieves created support request by ID`() {
            withMediator {
                val tenant = createTenant("Get Tenant")
                val created = createTestSupportRequest(tenant)
                val supportRequestId = SupportRequestId(created.id, TenantId(tenant.id))

                val found = GetSupportRequest(supportRequestId).query()

                assertThat(found).isNotNull
                assertThat(found!!.title).isEqualTo("Test Bug")
                assertThat(found.description).isEqualTo("Description for Test Bug")
            }
        }

        @Test
        fun `returns null for non-existent support request`() {
            withMediator {
                val tenant = createTenant("Get Missing")
                val supportRequestId = SupportRequestId(SupportRequestKey.generate(), TenantId(tenant.id))

                val found = GetSupportRequest(supportRequestId).query()

                assertThat(found).isNull()
            }
        }
    }

    @Nested
    inner class ListSupportRequestTests {
        @Test
        fun `lists support request for tenant`() {
            withMediator {
                val tenant = createTenant("List Tenant")
                createTestSupportRequest(tenant, "Bug 1")
                createTestSupportRequest(tenant, "Bug 2")

                val items = ListSupportRequest(tenantKey = tenant.id).query()

                assertThat(items).hasSize(2)
            }
        }

        @Test
        fun `filters by status`() {
            withMediator {
                val tenant = createTenant("Filter Status")
                val fb = createTestSupportRequest(tenant, "Open Bug")
                val fbId = SupportRequestId(fb.id, TenantId(tenant.id))
                UpdateSupportRequestStatus(id = fbId, status = SupportRequestStatus.CLOSED).execute()
                createTestSupportRequest(tenant, "Still Open")

                val openItems = ListSupportRequest(tenantKey = tenant.id, status = SupportRequestStatus.OPEN).query()
                val resolvedItems = ListSupportRequest(tenantKey = tenant.id, status = SupportRequestStatus.CLOSED).query()

                assertThat(openItems).hasSize(1)
                assertThat(openItems[0].title).isEqualTo("Still Open")
                assertThat(resolvedItems).hasSize(1)
                assertThat(resolvedItems[0].title).isEqualTo("Open Bug")
            }
        }

        @Test
        fun `filters by category`() {
            withMediator {
                val tenant = createTenant("Filter Category")
                createTestSupportRequest(tenant, "A Bug")
                val featureId = SupportRequestId(SupportRequestKey.generate(), TenantId(tenant.id))
                CreateSupportRequest(
                    id = featureId,
                    title = "A Feature",
                    description = "Feature desc",
                    category = SupportRequestCategory.FEATURE_REQUEST,
                    priority = SupportRequestPriority.LOW,
                    sourceUrl = null,
                    consoleLogs = null,
                    metadata = null,
                    createdBy = testUserKey,
                ).execute()

                val bugs = ListSupportRequest(tenantKey = tenant.id, category = SupportRequestCategory.BUG).query()
                val features = ListSupportRequest(tenantKey = tenant.id, category = SupportRequestCategory.FEATURE_REQUEST).query()

                assertThat(bugs).hasSize(1)
                assertThat(features).hasSize(1)
            }
        }
    }

    @Nested
    inner class CommentsTests {
        @Test
        fun `adds and retrieves local comment`() {
            withMediator {
                val tenant = createTenant("Comment Tenant")
                val fb = createTestSupportRequest(tenant)
                val supportRequestId = SupportRequestId(fb.id, TenantId(tenant.id))
                val commentId = SupportRequestCommentId(SupportRequestCommentKey.generate(), supportRequestId)

                val comment = AddSupportRequestComment(
                    id = commentId,
                    body = "This is a comment",
                    authorName = "Test User",
                    authorEmail = "test@example.com",
                    authorId = testUserKey,
                ).execute()

                assertThat(comment.body).isEqualTo("This is a comment")
                assertThat(comment.authorId).`as`("who wrote it, for the person reference").isEqualTo(testUserKey.value)
                assertThat(comment.source).isEqualTo(CommentSource.LOCAL)

                val comments = GetSupportRequestComments(supportRequestId).query()
                assertThat(comments).hasSize(1)
                assertThat(comments[0].body).isEqualTo("This is a comment")
            }
        }

        @Test
        fun `rejects blank comment body`() {
            assertThatThrownBy {
                AddSupportRequestComment(
                    id = SupportRequestCommentId(
                        SupportRequestCommentKey.generate(),
                        SupportRequestId(SupportRequestKey.generate(), TenantId(TenantKey.of("any"))),
                    ),
                    body = "  ",
                    authorName = "Test",
                    authorEmail = null,
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `deduplicates external comments by external_comment_id`() {
            withMediator {
                val tenant = createTenant("Dedup Tenant")
                val fb = createTestSupportRequest(tenant)

                val first = SyncSupportRequestComment(
                    tenantKey = tenant.id,
                    supportRequestId = fb.id,
                    body = "External comment",
                    authorName = "GitHub User",
                    authorEmail = "github@example.com",
                    externalCommentId = "github-123",
                ).execute()

                assertThat(first).isNotNull

                val duplicate = SyncSupportRequestComment(
                    tenantKey = tenant.id,
                    supportRequestId = fb.id,
                    body = "Same comment again",
                    authorName = "GitHub User",
                    authorEmail = "github@example.com",
                    externalCommentId = "github-123",
                ).execute()

                assertThat(duplicate).isNull()

                val supportRequestId = SupportRequestId(fb.id, TenantId(tenant.id))
                val comments = GetSupportRequestComments(supportRequestId).query()
                assertThat(comments).hasSize(1)
                assertThat(comments[0].source).isEqualTo(CommentSource.EXTERNAL)
            }
        }
    }

    @Nested
    inner class AssetsTests {
        @Test
        fun `stores and retrieves asset`() {
            withMediator {
                val tenant = createTenant("Asset Tenant")
                val fb = createTestSupportRequest(tenant)
                val supportRequestId = SupportRequestId(fb.id, TenantId(tenant.id))
                val assetId = SupportRequestAssetId(SupportRequestAssetKey.generate(), supportRequestId)
                val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

                val asset = AddSupportRequestAsset(
                    id = assetId,
                    content = pngBytes,
                    contentType = "image/png",
                    filename = "screenshot.png",
                ).execute()

                assertThat(asset.contentType).isEqualTo("image/png")
                assertThat(asset.filename).isEqualTo("screenshot.png")

                val assets = ListSupportRequestAssets(supportRequestId).query()
                assertThat(assets).hasSize(1)

                val content = GetSupportRequestAssetContent(assetId).query()
                assertThat(content).isNotNull
                assertThat(content!!.content).isEqualTo(pngBytes)
            }
        }

        @Test
        fun `fromDataUrl parses valid data URL`() {
            val assetId = SupportRequestAssetId(
                SupportRequestAssetKey.generate(),
                SupportRequestId(SupportRequestKey.generate(), TenantId(TenantKey.of("test"))),
            )
            val dataUrl = "data:image/png;base64,iVBORw0K"

            val command = AddSupportRequestAsset.fromDataUrl(assetId, dataUrl)

            assertThat(command).isNotNull
            assertThat(command!!.contentType).isEqualTo("image/png")
            assertThat(command.filename).isEqualTo("screenshot.png")
        }

        @Test
        fun `fromDataUrl returns null for malformed data URL`() {
            val assetId = SupportRequestAssetId(
                SupportRequestAssetKey.generate(),
                SupportRequestId(SupportRequestKey.generate(), TenantId(TenantKey.of("test"))),
            )

            assertThat(AddSupportRequestAsset.fromDataUrl(assetId, "not-a-data-url")).isNull()
            assertThat(AddSupportRequestAsset.fromDataUrl(assetId, "data:image/png;base64,!!!invalid!!!")).isNull()
        }

        @Test
        fun `fromDataUrl takes only the image types the support service accepts, nothing a browser would run`() {
            val assetId = SupportRequestAssetId(
                SupportRequestAssetKey.generate(),
                SupportRequestId(SupportRequestKey.generate(), TenantId(TenantKey.of("test"))),
            )
            val payload = java.util.Base64.getEncoder().encodeToString("<svg onload=alert(1)/>".toByteArray())

            assertThat(AddSupportRequestAsset.fromDataUrl(assetId, "data:image/svg+xml;base64,$payload")).isNull()
            assertThat(AddSupportRequestAsset.fromDataUrl(assetId, "data:text/html;base64,$payload")).isNull()
            assertThat(AddSupportRequestAsset.fromDataUrl(assetId, "data:image/gif;base64,R0lGOD")).isNull()
            assertThat(AddSupportRequestAsset.fromDataUrl(assetId, "data:image/png;name=x;base64,iVBORw0K")).isNull()
            assertThat(AddSupportRequestAsset.fromDataUrl(assetId, "data:image/jpeg;base64,/9j/4AAQ")!!.filename).isEqualTo("screenshot.jpeg")
            assertThat(AddSupportRequestAsset.fromDataUrl(assetId, "data:image/webp;base64,UklGRg")).isNotNull
        }

        @Test
        fun `fromDataUrl rejects content over the size cap`() {
            val assetId = SupportRequestAssetId(
                SupportRequestAssetKey.generate(),
                SupportRequestId(SupportRequestKey.generate(), TenantId(TenantKey.of("test"))),
            )
            val oversized = ByteArray(AddSupportRequestAsset.MAX_ASSET_BYTES + 1) { 1 }
            val dataUrl = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(oversized)

            assertThat(AddSupportRequestAsset.fromDataUrl(assetId, dataUrl)).isNull()
        }
    }

    @Nested
    inner class SyncStatusTests {
        @Test
        fun `updates sync status`() {
            withMediator {
                val tenant = createTenant("Sync Status")

                val fb = createTestSupportRequest(tenant)
                val supportRequestId = SupportRequestId(fb.id, TenantId(tenant.id))

                UpdateSupportRequestSyncRef(
                    id = supportRequestId,
                    externalRef = "42",
                    externalUrl = "https://github.com/test/repo/issues/42",
                    sentStatus = fb.status,
                ).execute()

                val synced = GetSupportRequest(supportRequestId).query()!!
                assertThat(synced.syncStatus).isEqualTo(SyncStatus.SYNCED)
                assertThat(synced.externalRef).isEqualTo("42")
                assertThat(synced.externalUrl).isEqualTo("https://github.com/test/repo/issues/42")
            }
        }

        @Test
        fun `increments sync attempts on failure`() {
            withMediator {
                val tenant = createTenant("Retry Count")

                val fb = createTestSupportRequest(tenant)
                val supportRequestId = SupportRequestId(fb.id, TenantId(tenant.id))

                UpdateSupportRequestSyncStatus(
                    id = supportRequestId,
                    syncStatus = SyncStatus.PENDING,
                    incrementAttempts = true,
                ).execute()

                val updated = GetSupportRequest(supportRequestId).query()!!
                assertThat(updated.syncAttempts).isEqualTo(1)
            }
        }

        @Test
        fun `ListPendingSyncSupportRequest excludes items at max attempts`() {
            withMediator {
                val tenant = createTenant("Max Attempts")

                val fb = createTestSupportRequest(tenant)
                val supportRequestId = SupportRequestId(fb.id, TenantId(tenant.id))

                // Increment to max attempts
                repeat(ListPendingSyncSupportRequest.MAX_SYNC_ATTEMPTS) {
                    UpdateSupportRequestSyncStatus(
                        id = supportRequestId,
                        syncStatus = SyncStatus.PENDING,
                        incrementAttempts = true,
                    ).execute()
                }

                val pending = ListPendingSyncSupportRequest(limit = 50).query()
                assertThat(pending.none { it.id == fb.id }).isTrue()
            }
        }
    }

    @Nested
    inner class SyncConfigTests {
        @Test
        fun `GetSupportRequestByExternalRef finds synced support request`() {
            withMediator {
                val tenant = createTenant("ExtRef Tenant")

                val fb = createTestSupportRequest(tenant)
                val supportRequestId = SupportRequestId(fb.id, TenantId(tenant.id))
                UpdateSupportRequestSyncRef(id = supportRequestId, externalRef = "99", externalUrl = "https://github.com/o/r/issues/99", sentStatus = fb.status).execute()

                val found = GetSupportRequestByExternalRef(tenant.id, "99").query()
                assertThat(found).isNotNull
                assertThat(found!!.key).isEqualTo(fb.id)
            }
        }

        @Test
        fun `GetSupportRequestByExternalRef returns null for unknown ref`() {
            withMediator {
                val tenant = createTenant("Unknown ExtRef")

                val found = GetSupportRequestByExternalRef(tenant.id, "nonexistent").query()
                assertThat(found).isNull()
            }
        }
    }

    @Nested
    inner class TenantIsolationTests {
        @Test
        fun `support request from one tenant is not visible to another`() {
            withMediator {
                val tenant1 = createTenant("Iso Tenant 1")
                val tenant2 = createTenant("Iso Tenant 2")

                createTestSupportRequest(tenant1, "Tenant 1 Bug")
                createTestSupportRequest(tenant2, "Tenant 2 Bug")

                val t1Items = ListSupportRequest(tenantKey = tenant1.id).query()
                val t2Items = ListSupportRequest(tenantKey = tenant2.id).query()

                assertThat(t1Items).hasSize(1)
                assertThat(t1Items[0].title).isEqualTo("Tenant 1 Bug")
                assertThat(t2Items).hasSize(1)
                assertThat(t2Items[0].title).isEqualTo("Tenant 2 Bug")
            }
        }

        @Test
        fun `GetSupportRequest returns null when querying wrong tenant`() {
            withMediator {
                val tenant1 = createTenant("Cross Tenant 1")
                val tenant2 = createTenant("Cross Tenant 2")

                val fb = createTestSupportRequest(tenant1, "Tenant 1 Only")
                val wrongId = SupportRequestId(fb.id, TenantId(tenant2.id))

                val found = GetSupportRequest(wrongId).query()
                assertThat(found).isNull()
            }
        }
    }
}

package app.epistola.suite.feedback

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.ids.FeedbackAssetId
import app.epistola.suite.common.ids.FeedbackAssetKey
import app.epistola.suite.common.ids.FeedbackCommentId
import app.epistola.suite.common.ids.FeedbackCommentKey
import app.epistola.suite.common.ids.FeedbackId
import app.epistola.suite.common.ids.FeedbackKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.feedback.commands.AddFeedbackAsset
import app.epistola.suite.feedback.commands.AddFeedbackComment
import app.epistola.suite.feedback.commands.CreateFeedback
import app.epistola.suite.feedback.commands.SaveFeedbackSyncConfig
import app.epistola.suite.feedback.commands.SyncFeedbackComment
import app.epistola.suite.feedback.commands.UpdateFeedbackStatus
import app.epistola.suite.feedback.commands.UpdateFeedbackSyncRef
import app.epistola.suite.feedback.commands.UpdateFeedbackSyncStatus
import app.epistola.suite.feedback.queries.GetFeedback
import app.epistola.suite.feedback.queries.GetFeedbackAssetContent
import app.epistola.suite.feedback.queries.GetFeedbackByExternalRef
import app.epistola.suite.feedback.queries.GetFeedbackComments
import app.epistola.suite.feedback.queries.GetFeedbackSyncConfig
import app.epistola.suite.feedback.queries.ListFeedback
import app.epistola.suite.feedback.queries.ListFeedbackAssets
import app.epistola.suite.feedback.queries.ListPendingSyncFeedback
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import javax.sql.DataSource

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeedbackIntegrationTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var dataSource: DataSource

    private val testUserKey = UserKey.of("00000000-0000-0000-0000-feedbac00099")

    @BeforeAll
    fun seedTestUser() {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, external_id, email, display_name, provider, enabled, created_at)
                VALUES (?, 'feedback-test-user', 'feedback-test@example.com', 'Feedback Test User', 'LOCAL', true, NOW())
                ON CONFLICT (external_id, provider) DO NOTHING
                """,
            ).use { stmt ->
                stmt.setObject(1, testUserKey.value)
                stmt.executeUpdate()
            }
        }
    }

    private fun createTestFeedback(tenant: Tenant, title: String = "Test Bug"): Feedback = withMediator {
        val feedbackId = FeedbackId(FeedbackKey.generate(), TenantId(tenant.id))
        CreateFeedback(
            id = feedbackId,
            title = title,
            description = "Description for $title",
            category = FeedbackCategory.BUG,
            priority = FeedbackPriority.MEDIUM,
            sourceUrl = "https://example.com/page",
            consoleLogs = null,
            metadata = null,
            createdBy = testUserKey,
        ).execute()
    }

    private fun configureSyncForTenant(tenant: Tenant) {
        withMediator {
            SaveFeedbackSyncConfig(
                tenantKey = tenant.id,
                enabled = true,
                providerType = SyncProviderType.GITHUB,
                settings = """{"personalAccessToken": "ghp_test", "repoOwner": "test", "repoName": "repo", "label": "feedback"}""",
            ).execute()
        }
    }

    @Nested
    inner class CreateFeedbackTests {
        @Test
        fun `creates feedback with correct fields`() {
            withMediator {
                val tenant = createTenant("Feedback Tenant")
                val feedbackId = FeedbackId(FeedbackKey.generate(), TenantId(tenant.id))

                val feedback = CreateFeedback(
                    id = feedbackId,
                    title = "Login fails on Safari",
                    description = "Steps to reproduce: open Safari, click login",
                    category = FeedbackCategory.BUG,
                    priority = FeedbackPriority.HIGH,
                    sourceUrl = "https://app.example.com/login",
                    consoleLogs = "[ERROR] auth failed",
                    metadata = """{"browser": "Safari"}""",
                    createdBy = testUserKey,
                ).execute()

                assertThat(feedback.id).isEqualTo(feedbackId.key)
                assertThat(feedback.title).isEqualTo("Login fails on Safari")
                assertThat(feedback.category).isEqualTo(FeedbackCategory.BUG)
                assertThat(feedback.priority).isEqualTo(FeedbackPriority.HIGH)
                assertThat(feedback.status).isEqualTo(FeedbackStatus.OPEN)
                assertThat(feedback.syncStatus).isEqualTo(SyncStatus.NOT_CONFIGURED)
                assertThat(feedback.syncAttempts).isEqualTo(0)
            }
        }

        @Test
        fun `sets sync status to PENDING when sync is configured`() {
            withMediator {
                val tenant = createTenant("Sync Tenant")
                configureSyncForTenant(tenant)

                val feedbackId = FeedbackId(FeedbackKey.generate(), TenantId(tenant.id))
                val feedback = CreateFeedback(
                    id = feedbackId,
                    title = "Feature request",
                    description = "Please add dark mode",
                    category = FeedbackCategory.FEATURE_REQUEST,
                    priority = FeedbackPriority.LOW,
                    sourceUrl = null,
                    consoleLogs = null,
                    metadata = null,
                    createdBy = testUserKey,
                ).execute()

                assertThat(feedback.syncStatus).isEqualTo(SyncStatus.PENDING)
            }
        }

        @Test
        fun `rejects blank title`() {
            assertThatThrownBy {
                CreateFeedback(
                    id = FeedbackId(FeedbackKey.generate(), TenantId(TenantKey.of("any"))),
                    title = "  ",
                    description = "desc",
                    category = FeedbackCategory.BUG,
                    priority = FeedbackPriority.MEDIUM,
                    sourceUrl = null,
                    consoleLogs = null,
                    metadata = null,
                    createdBy = testUserKey,
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    inner class GetFeedbackTests {
        @Test
        fun `retrieves created feedback by ID`() {
            withMediator {
                val tenant = createTenant("Get Tenant")
                val created = createTestFeedback(tenant)
                val feedbackId = FeedbackId(created.id, TenantId(tenant.id))

                val found = GetFeedback(feedbackId).query()

                assertThat(found).isNotNull
                assertThat(found!!.title).isEqualTo("Test Bug")
                assertThat(found.description).isEqualTo("Description for Test Bug")
            }
        }

        @Test
        fun `returns null for non-existent feedback`() {
            withMediator {
                val tenant = createTenant("Get Missing")
                val feedbackId = FeedbackId(FeedbackKey.generate(), TenantId(tenant.id))

                val found = GetFeedback(feedbackId).query()

                assertThat(found).isNull()
            }
        }
    }

    @Nested
    inner class ListFeedbackTests {
        @Test
        fun `lists feedback for tenant`() {
            withMediator {
                val tenant = createTenant("List Tenant")
                createTestFeedback(tenant, "Bug 1")
                createTestFeedback(tenant, "Bug 2")

                val items = ListFeedback(tenantKey = tenant.id).query()

                assertThat(items).hasSize(2)
            }
        }

        @Test
        fun `filters by status`() {
            withMediator {
                val tenant = createTenant("Filter Status")
                val fb = createTestFeedback(tenant, "Open Bug")
                val fbId = FeedbackId(fb.id, TenantId(tenant.id))
                UpdateFeedbackStatus(id = fbId, status = FeedbackStatus.RESOLVED).execute()
                createTestFeedback(tenant, "Still Open")

                val openItems = ListFeedback(tenantKey = tenant.id, status = FeedbackStatus.OPEN).query()
                val resolvedItems = ListFeedback(tenantKey = tenant.id, status = FeedbackStatus.RESOLVED).query()

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
                createTestFeedback(tenant, "A Bug")
                val featureId = FeedbackId(FeedbackKey.generate(), TenantId(tenant.id))
                CreateFeedback(
                    id = featureId,
                    title = "A Feature",
                    description = "Feature desc",
                    category = FeedbackCategory.FEATURE_REQUEST,
                    priority = FeedbackPriority.LOW,
                    sourceUrl = null,
                    consoleLogs = null,
                    metadata = null,
                    createdBy = testUserKey,
                ).execute()

                val bugs = ListFeedback(tenantKey = tenant.id, category = FeedbackCategory.BUG).query()
                val features = ListFeedback(tenantKey = tenant.id, category = FeedbackCategory.FEATURE_REQUEST).query()

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
                val fb = createTestFeedback(tenant)
                val feedbackId = FeedbackId(fb.id, TenantId(tenant.id))
                val commentId = FeedbackCommentId(FeedbackCommentKey.generate(), feedbackId)

                val comment = AddFeedbackComment(
                    id = commentId,
                    body = "This is a comment",
                    authorName = "Test User",
                    authorEmail = "test@example.com",
                ).execute()

                assertThat(comment.body).isEqualTo("This is a comment")
                assertThat(comment.source).isEqualTo(CommentSource.LOCAL)

                val comments = GetFeedbackComments(feedbackId).query()
                assertThat(comments).hasSize(1)
                assertThat(comments[0].body).isEqualTo("This is a comment")
            }
        }

        @Test
        fun `rejects blank comment body`() {
            assertThatThrownBy {
                AddFeedbackComment(
                    id = FeedbackCommentId(
                        FeedbackCommentKey.generate(),
                        FeedbackId(FeedbackKey.generate(), TenantId(TenantKey.of("any"))),
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
                val fb = createTestFeedback(tenant)

                val first = SyncFeedbackComment(
                    tenantKey = tenant.id,
                    feedbackId = fb.id,
                    body = "External comment",
                    authorName = "GitHub User",
                    authorEmail = "github@example.com",
                    externalCommentId = "github-123",
                ).execute()

                assertThat(first).isNotNull

                val duplicate = SyncFeedbackComment(
                    tenantKey = tenant.id,
                    feedbackId = fb.id,
                    body = "Same comment again",
                    authorName = "GitHub User",
                    authorEmail = "github@example.com",
                    externalCommentId = "github-123",
                ).execute()

                assertThat(duplicate).isNull()

                val feedbackId = FeedbackId(fb.id, TenantId(tenant.id))
                val comments = GetFeedbackComments(feedbackId).query()
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
                val fb = createTestFeedback(tenant)
                val feedbackId = FeedbackId(fb.id, TenantId(tenant.id))
                val assetId = FeedbackAssetId(FeedbackAssetKey.generate(), feedbackId)
                val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

                val asset = AddFeedbackAsset(
                    id = assetId,
                    content = pngBytes,
                    contentType = "image/png",
                    filename = "screenshot.png",
                ).execute()

                assertThat(asset.contentType).isEqualTo("image/png")
                assertThat(asset.filename).isEqualTo("screenshot.png")

                val assets = ListFeedbackAssets(feedbackId).query()
                assertThat(assets).hasSize(1)

                val content = GetFeedbackAssetContent(assetId).query()
                assertThat(content).isNotNull
                assertThat(content!!.content).isEqualTo(pngBytes)
            }
        }

        @Test
        fun `fromDataUrl parses valid data URL`() {
            val assetId = FeedbackAssetId(
                FeedbackAssetKey.generate(),
                FeedbackId(FeedbackKey.generate(), TenantId(TenantKey.of("test"))),
            )
            val dataUrl = "data:image/png;base64,iVBORw0K"

            val command = AddFeedbackAsset.fromDataUrl(assetId, dataUrl)

            assertThat(command).isNotNull
            assertThat(command!!.contentType).isEqualTo("image/png")
            assertThat(command.filename).isEqualTo("screenshot.png")
        }

        @Test
        fun `fromDataUrl returns null for malformed data URL`() {
            val assetId = FeedbackAssetId(
                FeedbackAssetKey.generate(),
                FeedbackId(FeedbackKey.generate(), TenantId(TenantKey.of("test"))),
            )

            assertThat(AddFeedbackAsset.fromDataUrl(assetId, "not-a-data-url")).isNull()
            assertThat(AddFeedbackAsset.fromDataUrl(assetId, "data:image/png;base64,!!!invalid!!!")).isNull()
        }
    }

    @Nested
    inner class SyncStatusTests {
        @Test
        fun `updates sync status`() {
            withMediator {
                val tenant = createTenant("Sync Status")
                configureSyncForTenant(tenant)

                val fb = createTestFeedback(tenant)
                val feedbackId = FeedbackId(fb.id, TenantId(tenant.id))

                UpdateFeedbackSyncRef(
                    id = feedbackId,
                    externalRef = "42",
                    externalUrl = "https://github.com/test/repo/issues/42",
                ).execute()

                val synced = GetFeedback(feedbackId).query()!!
                assertThat(synced.syncStatus).isEqualTo(SyncStatus.SYNCED)
                assertThat(synced.externalRef).isEqualTo("42")
                assertThat(synced.externalUrl).isEqualTo("https://github.com/test/repo/issues/42")
            }
        }

        @Test
        fun `increments sync attempts on failure`() {
            withMediator {
                val tenant = createTenant("Retry Count")
                configureSyncForTenant(tenant)

                val fb = createTestFeedback(tenant)
                val feedbackId = FeedbackId(fb.id, TenantId(tenant.id))

                UpdateFeedbackSyncStatus(
                    id = feedbackId,
                    syncStatus = SyncStatus.PENDING,
                    incrementAttempts = true,
                ).execute()

                val updated = GetFeedback(feedbackId).query()!!
                assertThat(updated.syncAttempts).isEqualTo(1)
            }
        }

        @Test
        fun `ListPendingSyncFeedback excludes items at max attempts`() {
            withMediator {
                val tenant = createTenant("Max Attempts")
                configureSyncForTenant(tenant)

                val fb = createTestFeedback(tenant)
                val feedbackId = FeedbackId(fb.id, TenantId(tenant.id))

                // Increment to max attempts
                repeat(ListPendingSyncFeedback.MAX_SYNC_ATTEMPTS) {
                    UpdateFeedbackSyncStatus(
                        id = feedbackId,
                        syncStatus = SyncStatus.PENDING,
                        incrementAttempts = true,
                    ).execute()
                }

                val pending = ListPendingSyncFeedback(limit = 50).query()
                assertThat(pending.none { it.id == fb.id }).isTrue()
            }
        }
    }

    @Nested
    inner class SyncConfigTests {
        @Test
        fun `saves and retrieves sync config`() {
            withMediator {
                val tenant = createTenant("Config Tenant")

                val saved = SaveFeedbackSyncConfig(
                    tenantKey = tenant.id,
                    enabled = true,
                    providerType = SyncProviderType.GITHUB,
                    settings = """{"personalAccessToken": "ghp_test123", "repoOwner": "acme", "repoName": "issues", "label": "feedback"}""",
                ).execute()

                assertThat(saved.enabled).isTrue()
                assertThat(saved.providerType).isEqualTo(SyncProviderType.GITHUB)

                val config = GetFeedbackSyncConfig(tenant.id).query()
                assertThat(config).isNotNull
                assertThat(config!!.enabled).isTrue()
            }
        }

        @Test
        fun `GetFeedbackByExternalRef finds synced feedback`() {
            withMediator {
                val tenant = createTenant("ExtRef Tenant")
                configureSyncForTenant(tenant)

                val fb = createTestFeedback(tenant)
                val feedbackId = FeedbackId(fb.id, TenantId(tenant.id))
                UpdateFeedbackSyncRef(id = feedbackId, externalRef = "99", externalUrl = "https://github.com/o/r/issues/99").execute()

                val found = GetFeedbackByExternalRef(tenant.id, "99").query()
                assertThat(found).isNotNull
                assertThat(found!!.key).isEqualTo(fb.id)
            }
        }

        @Test
        fun `GetFeedbackByExternalRef returns null for unknown ref`() {
            withMediator {
                val tenant = createTenant("Unknown ExtRef")

                val found = GetFeedbackByExternalRef(tenant.id, "nonexistent").query()
                assertThat(found).isNull()
            }
        }
    }

    @Nested
    inner class TenantIsolationTests {
        @Test
        fun `feedback from one tenant is not visible to another`() {
            withMediator {
                val tenant1 = createTenant("Iso Tenant 1")
                val tenant2 = createTenant("Iso Tenant 2")

                createTestFeedback(tenant1, "Tenant 1 Bug")
                createTestFeedback(tenant2, "Tenant 2 Bug")

                val t1Items = ListFeedback(tenantKey = tenant1.id).query()
                val t2Items = ListFeedback(tenantKey = tenant2.id).query()

                assertThat(t1Items).hasSize(1)
                assertThat(t1Items[0].title).isEqualTo("Tenant 1 Bug")
                assertThat(t2Items).hasSize(1)
                assertThat(t2Items[0].title).isEqualTo("Tenant 2 Bug")
            }
        }

        @Test
        fun `GetFeedback returns null when querying wrong tenant`() {
            withMediator {
                val tenant1 = createTenant("Cross Tenant 1")
                val tenant2 = createTenant("Cross Tenant 2")

                val fb = createTestFeedback(tenant1, "Tenant 1 Only")
                val wrongId = FeedbackId(fb.id, TenantId(tenant2.id))

                val found = GetFeedback(wrongId).query()
                assertThat(found).isNull()
            }
        }
    }
}

package app.epistola.suite.feedback.sync.github

import app.epistola.suite.common.ids.FeedbackCommentKey
import app.epistola.suite.common.ids.FeedbackKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.feedback.CommentSource
import app.epistola.suite.feedback.Feedback
import app.epistola.suite.feedback.FeedbackAssetContent
import app.epistola.suite.feedback.FeedbackCategory
import app.epistola.suite.feedback.FeedbackComment
import app.epistola.suite.feedback.FeedbackPriority
import app.epistola.suite.feedback.FeedbackStatus
import app.epistola.suite.feedback.FeedbackSyncConfig
import app.epistola.suite.feedback.SyncProviderType
import app.epistola.suite.feedback.SyncStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val BASE_URL = "https://api.github.com"

class GitHubIssueSyncAdapterTest {

    private val objectMapper = jsonMapper { addModule(kotlinModule()) }
    private lateinit var mockServer: MockRestServiceServer
    private lateinit var adapter: GitHubIssueSyncAdapter

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder()
            .baseUrl(BASE_URL)
            .defaultHeader("Accept", "application/vnd.github+json")
        mockServer = MockRestServiceServer.bindTo(builder).build()
        adapter = GitHubIssueSyncAdapter(builder.build(), objectMapper)
    }

    @Nested
    inner class CreateTicket {

        @Test
        fun `creates issue with correct body and default tenant label when no assets`() {
            val feedback = buildFeedback()
            val config = buildConfig()

            mockServer.expect(requestTo("$BASE_URL/repos/test-owner/test-repo/issues"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""{"title": "Test feedback title"}"""))
                .andExpect(
                    content().json(
                        """{"labels": ["feedback", "bug", "priority:medium", "etk-test-tenant"]}""",
                    ),
                )
                .andRespond(
                    withSuccess(
                        """{"number": 42, "html_url": "https://github.com/test-owner/test-repo/issues/42"}""",
                        MediaType.APPLICATION_JSON,
                    ),
                )

            val result = adapter.createTicket(config, feedback, emptyList())

            assertEquals("42", result.externalRef)
            assertEquals("https://github.com/test-owner/test-repo/issues/42", result.externalUrl)
            mockServer.verify()
        }

        @Test
        fun `uses custom label override when configured`() {
            val feedback = buildFeedback(
                category = FeedbackCategory.FEATURE_REQUEST,
                priority = FeedbackPriority.CRITICAL,
            )
            val config = buildConfig(label = "my-custom-label")

            mockServer.expect(requestTo("$BASE_URL/repos/test-owner/test-repo/issues"))
                .andExpect(
                    content().json(
                        """{"labels": ["feedback", "feature-request", "priority:critical", "my-custom-label"]}""",
                    ),
                )
                .andRespond(
                    withSuccess(
                        """{"number": 1, "html_url": "https://github.com/test-owner/test-repo/issues/1"}""",
                        MediaType.APPLICATION_JSON,
                    ),
                )

            adapter.createTicket(config, feedback, emptyList())
            mockServer.verify()
        }

        @Test
        fun `uploads screenshot and embeds in issue body`() {
            val feedback = buildFeedback()
            val config = buildConfig()
            val screenshotBytes = "fake-png-data".toByteArray()
            val asset = FeedbackAssetContent(
                content = screenshotBytes,
                contentType = "image/png",
                filename = "screenshot.png",
            )

            // Expect asset upload via Contents API
            mockServer.expect(requestTo(contentsUrl(feedback.id.value, "png")))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(
                    content().json(
                        """{"content": "${Base64.getEncoder().encodeToString(screenshotBytes)}"}""",
                    ),
                )
                .andRespond(
                    withSuccess(
                        """{"content": {"download_url": "https://raw.githubusercontent.com/test-owner/test-repo/main/.epistola/screenshots/shot.png"}}""",
                        MediaType.APPLICATION_JSON,
                    ),
                )

            // Expect issue creation with embedded screenshot
            var capturedBody = ""
            mockServer.expect(requestTo("$BASE_URL/repos/test-owner/test-repo/issues"))
                .andExpect(method(HttpMethod.POST))
                .andExpect { request ->
                    capturedBody = (request as org.springframework.mock.http.client.MockClientHttpRequest).bodyAsString
                }
                .andRespond(
                    withSuccess(
                        """{"number": 7, "html_url": "https://github.com/test-owner/test-repo/issues/7"}""",
                        MediaType.APPLICATION_JSON,
                    ),
                )

            val result = adapter.createTicket(config, feedback, listOf(asset))

            assertEquals("7", result.externalRef)
            val issueBody = objectMapper.readTree(capturedBody).path("body").stringValue()!!
            assertTrue(issueBody.contains("![Screenshot](https://raw.githubusercontent.com/test-owner/test-repo/main/.epistola/screenshots/shot.png)"))
            mockServer.verify()
        }

        @Test
        fun `creates issue without screenshot when asset upload fails`() {
            val feedback = buildFeedback()
            val config = buildConfig()
            val asset = FeedbackAssetContent(
                content = "png-data".toByteArray(),
                contentType = "image/png",
                filename = "screenshot.png",
            )

            // Asset upload fails
            mockServer.expect(requestTo(contentsUrl(feedback.id.value, "png")))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withServerError())

            // Issue creation should still succeed (no screenshot embedded)
            var capturedBody = ""
            mockServer.expect(requestTo("$BASE_URL/repos/test-owner/test-repo/issues"))
                .andExpect(method(HttpMethod.POST))
                .andExpect { request ->
                    capturedBody = (request as org.springframework.mock.http.client.MockClientHttpRequest).bodyAsString
                }
                .andRespond(
                    withSuccess(
                        """{"number": 3, "html_url": "https://github.com/test-owner/test-repo/issues/3"}""",
                        MediaType.APPLICATION_JSON,
                    ),
                )

            val result = adapter.createTicket(config, feedback, listOf(asset))

            assertEquals("3", result.externalRef)
            val issueBody = objectMapper.readTree(capturedBody).path("body").stringValue()!!
            assertFalse(issueBody.contains("![Screenshot"))
            mockServer.verify()
        }

        @Test
        fun `handles multiple assets with numbered labels`() {
            val feedback = buildFeedback()
            val config = buildConfig()
            val asset1 = FeedbackAssetContent("img1".toByteArray(), "image/png", "a.png")
            val asset2 = FeedbackAssetContent("img2".toByteArray(), "image/jpeg", "b.jpg")

            // Two upload requests
            mockServer.expect(requestTo(contentsUrl(feedback.id.value, "png")))
                .andRespond(
                    withSuccess(
                        """{"content": {"download_url": "https://raw.example.com/shot1.png"}}""",
                        MediaType.APPLICATION_JSON,
                    ),
                )

            mockServer.expect(requestTo(contentsUrl(feedback.id.value, "jpg")))
                .andRespond(
                    withSuccess(
                        """{"content": {"download_url": "https://raw.example.com/shot2.jpg"}}""",
                        MediaType.APPLICATION_JSON,
                    ),
                )

            // Issue creation — verify body contains numbered screenshot labels
            var capturedBody = ""
            mockServer.expect(requestTo("$BASE_URL/repos/test-owner/test-repo/issues"))
                .andExpect(method(HttpMethod.POST))
                .andExpect { request ->
                    capturedBody = (request as org.springframework.mock.http.client.MockClientHttpRequest).bodyAsString
                }
                .andRespond(
                    withSuccess(
                        """{"number": 5, "html_url": "https://github.com/test-owner/test-repo/issues/5"}""",
                        MediaType.APPLICATION_JSON,
                    ),
                )

            adapter.createTicket(config, feedback, listOf(asset1, asset2))

            val issueBody = objectMapper.readTree(capturedBody).path("body").stringValue()!!
            assertTrue(issueBody.contains("![Screenshot 1](https://raw.example.com/shot1.png)"))
            assertTrue(issueBody.contains("![Screenshot 2](https://raw.example.com/shot2.jpg)"))
            mockServer.verify()
        }

        @Test
        fun `includes console logs in collapsible details block`() {
            val feedback = buildFeedback(consoleLogs = "Error: something broke\nat line 42")
            val config = buildConfig()

            var capturedBody = ""
            mockServer.expect(requestTo("$BASE_URL/repos/test-owner/test-repo/issues"))
                .andExpect { request ->
                    capturedBody = (request as org.springframework.mock.http.client.MockClientHttpRequest).bodyAsString
                }
                .andRespond(
                    withSuccess(
                        """{"number": 10, "html_url": "https://github.com/test-owner/test-repo/issues/10"}""",
                        MediaType.APPLICATION_JSON,
                    ),
                )

            adapter.createTicket(config, feedback, emptyList())

            val issueBody = objectMapper.readTree(capturedBody).path("body").stringValue()!!
            assertTrue(issueBody.contains("<details>"))
            assertTrue(issueBody.contains("<summary>Console Logs</summary>"))
            assertTrue(issueBody.contains("Error: something broke"))
            mockServer.verify()
        }

        @Test
        fun `does not include console logs section when null`() {
            val feedback = buildFeedback(consoleLogs = null)
            val config = buildConfig()

            var capturedBody = ""
            mockServer.expect(requestTo("$BASE_URL/repos/test-owner/test-repo/issues"))
                .andExpect { request ->
                    capturedBody = (request as org.springframework.mock.http.client.MockClientHttpRequest).bodyAsString
                }
                .andRespond(
                    withSuccess(
                        """{"number": 11, "html_url": "https://github.com/test-owner/test-repo/issues/11"}""",
                        MediaType.APPLICATION_JSON,
                    ),
                )

            adapter.createTicket(config, feedback, emptyList())

            val issueBody = objectMapper.readTree(capturedBody).path("body").stringValue()!!
            assertFalse(issueBody.contains("<details>"))
            assertFalse(issueBody.contains("Console Logs"))
            mockServer.verify()
        }
    }

    @Nested
    inner class AddComment {

        @Test
        fun `posts comment with author attribution`() {
            val config = buildConfig()
            val comment = FeedbackComment(
                id = FeedbackCommentKey.generate(),
                feedbackId = FeedbackKey.generate(),
                tenantKey = TenantKey("test-tenant"),
                body = "This needs more info",
                authorName = "Jane Doe",
                authorEmail = "jane@example.com",
                source = CommentSource.LOCAL,
                externalCommentId = null,
                createdAt = OffsetDateTime.now(),
            )

            mockServer.expect(requestTo("$BASE_URL/repos/test-owner/test-repo/issues/42/comments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(content().json("""{"body": "**Jane Doe** commented:\n\nThis needs more info"}"""))
                .andRespond(
                    withSuccess(
                        """{"id": 999}""",
                        MediaType.APPLICATION_JSON,
                    ),
                )

            val result = adapter.addComment(config, "42", comment)

            assertEquals("999", result.externalCommentId)
            mockServer.verify()
        }
    }

    @Nested
    inner class UpdateStatus {

        @Test
        fun `closes issue for RESOLVED status`() {
            val config = buildConfig()

            mockServer.expect(requestTo("$BASE_URL/repos/test-owner/test-repo/issues/42"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(content().json("""{"state": "closed"}"""))
                .andRespond(withSuccess())

            adapter.updateStatus(config, "42", FeedbackStatus.RESOLVED)

            mockServer.verify()
        }

        @Test
        fun `closes issue for CLOSED status`() {
            val config = buildConfig()

            mockServer.expect(requestTo("$BASE_URL/repos/test-owner/test-repo/issues/42"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json("""{"state": "closed"}"""))
                .andRespond(withSuccess())

            adapter.updateStatus(config, "42", FeedbackStatus.CLOSED)

            mockServer.verify()
        }

        @Test
        fun `opens issue for OPEN status`() {
            val config = buildConfig()

            mockServer.expect(requestTo("$BASE_URL/repos/test-owner/test-repo/issues/15"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json("""{"state": "open"}"""))
                .andRespond(withSuccess())

            adapter.updateStatus(config, "15", FeedbackStatus.OPEN)

            mockServer.verify()
        }

        @Test
        fun `opens issue for IN_PROGRESS status`() {
            val config = buildConfig()

            mockServer.expect(requestTo("$BASE_URL/repos/test-owner/test-repo/issues/15"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json("""{"state": "open"}"""))
                .andRespond(withSuccess())

            adapter.updateStatus(config, "15", FeedbackStatus.IN_PROGRESS)

            mockServer.verify()
        }
    }

    // -- Test helpers --

    private val fixedFeedbackId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private fun contentsUrl(feedbackId: UUID, extension: String): String = "$BASE_URL/repos/test-owner/test-repo/contents/.epistola%2Fscreenshots%2F$feedbackId.$extension"

    private fun buildConfig(label: String? = null): FeedbackSyncConfig {
        val settings = GitHubSyncSettings(
            personalAccessToken = "test-token",
            repoOwner = "test-owner",
            repoName = "test-repo",
            label = label,
        )
        return FeedbackSyncConfig(
            tenantKey = TenantKey("test-tenant"),
            enabled = true,
            providerType = SyncProviderType.GITHUB,
            settings = objectMapper.writeValueAsString(settings),
            lastPolledAt = null,
        )
    }

    private fun buildFeedback(
        id: UUID = fixedFeedbackId,
        category: FeedbackCategory = FeedbackCategory.BUG,
        priority: FeedbackPriority = FeedbackPriority.MEDIUM,
        consoleLogs: String? = null,
        sourceUrl: String? = "https://app.example.com/editor",
    ): Feedback = Feedback(
        id = FeedbackKey.of(id),
        tenantKey = TenantKey("test-tenant"),
        title = "Test feedback title",
        description = "Something is broken",
        category = category,
        status = FeedbackStatus.OPEN,
        priority = priority,
        sourceUrl = sourceUrl,
        consoleLogs = consoleLogs,
        metadata = null,
        createdBy = UserKey.of(UUID.fromString("00000000-0000-0000-0000-000000000099")),
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now(),
        externalRef = null,
        externalUrl = null,
        syncStatus = SyncStatus.PENDING,
    )
}

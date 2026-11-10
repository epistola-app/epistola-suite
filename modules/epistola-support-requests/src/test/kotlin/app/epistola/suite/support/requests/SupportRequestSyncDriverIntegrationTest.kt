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
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.metadata.getAs
import app.epistola.suite.support.connection.EnsureConfiguredSupportConnection
import app.epistola.suite.support.connection.SupportBackgroundWork
import app.epistola.suite.support.connection.SupportConnection
import app.epistola.suite.support.connection.SupportConnectionState
import app.epistola.suite.support.connection.SupportConnectionStatus
import app.epistola.suite.support.connection.SupportScope
import app.epistola.suite.support.connection.SupportUnavailableException
import app.epistola.suite.support.requests.commands.AddSupportRequestAsset
import app.epistola.suite.support.requests.commands.AddSupportRequestComment
import app.epistola.suite.support.requests.commands.CreateSupportRequest
import app.epistola.suite.support.requests.commands.SyncSupportRequestComment
import app.epistola.suite.support.requests.commands.SyncSupportRequestStatus
import app.epistola.suite.support.requests.commands.UpdateSupportRequestStatus
import app.epistola.suite.support.requests.commands.UpdateSupportRequestSyncRef
import app.epistola.suite.support.requests.commands.UpdateSupportRequestSyncStatus
import app.epistola.suite.support.requests.consent.GetSupportRequestSharingSettings
import app.epistola.suite.support.requests.consent.GrantSupportRequestSyncConsent
import app.epistola.suite.support.requests.consent.WithdrawSupportRequestSyncConsent
import app.epistola.suite.support.requests.desk.GetSupportRequestHandling
import app.epistola.suite.support.requests.queries.GetSupportRequest
import app.epistola.suite.support.requests.queries.GetSupportRequestComments
import app.epistola.suite.support.requests.queries.ListPendingSyncSupportRequest
import app.epistola.suite.support.requests.queries.ListSupportRequestAssets
import app.epistola.suite.support.requests.queries.ListUnsyncedComments
import app.epistola.suite.support.requests.queries.ListUnsyncedSupportRequestAssets
import app.epistola.suite.support.requests.sync.ExternalUpdate
import app.epistola.suite.support.requests.sync.ExternalUpdatePage
import app.epistola.suite.support.requests.sync.ListUnsentSupportRequestStatuses
import app.epistola.suite.support.requests.sync.SupportRequestPollScheduler
import app.epistola.suite.support.requests.sync.SupportRequestRoundLock
import app.epistola.suite.support.requests.sync.SupportRequestSyncPort
import app.epistola.suite.support.requests.sync.SupportRequestSyncScheduler
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.IntegrationTestBase
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Exercises the feedback sync machinery (event-handler push path, poll-back, retry sweep)
 * against a [RecordingSupportRequestSyncPort] standing in for the support service adapter. The push handlers run
 * in the IMMEDIATE phase, so executing a command through the mediator drives them directly.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(SupportRequestSyncDriverIntegrationTest.TestConfig::class)
@TestPropertySource(
    properties = [
        "epistola.support.requests.sync.enabled=true",
        "epistola.support.requests.sync.retry-interval-ms=3600000",
        "epistola.support.requests.sync.polling.enabled=true",
        "epistola.support.requests.sync.polling.interval-ms=3600000",
        // These tests are about sending: Epistola support's connection (the configured default endpoint) handles new requests.
        "epistola.support.requests.handled-by=https://support.epistola.app",
    ],
)
class SupportRequestSyncDriverIntegrationTest : IntegrationTestBase() {

    /**
     * Stands in for the real [SupportConnection] so a test can simulate the support service being
     * unreachable and assert the sweeps back off. The support connection is off in this context, so
     * the real bean never reports a failed contact; this lets the last contact fail per test.
     */
    class ToggleableSupportConnection : SupportConnection {
        override val id: java.util.UUID = app.epistola.suite.support.connection.SupportConnectionStore.NO_CONNECTION

        @Volatile
        var serviceReachable: Boolean = true

        override fun status() = SupportConnectionStatus(
            state = SupportConnectionState.Active("sc_test", SupportScope.entries.toSet()),
            issuer = null,
            checkedAt = null,
            lastError = if (serviceReachable) null else "connection refused",
        )

        override fun hasScope(scope: SupportScope) = true
        override fun isSelected(scope: SupportScope) = true
        override fun scopeRefused(scope: SupportScope) = Unit
        override fun apiEndpoint(): URI? = null
        override fun otlpEndpoint(): URI? = null
        override fun cachedAccessToken(): String? = null
        override fun accessToken(): String = throw SupportUnavailableException("not in this test")
        override fun restClient(): RestClient = throw SupportUnavailableException("not in this test")
        override fun checkNow() = Unit
    }

    class TestConfig {
        @Bean
        @Primary
        fun recordingSupportRequestSyncPort(): SupportRequestSyncPort = RecordingSupportRequestSyncPort()

        @Bean
        @Primary
        fun toggleableSupportConnection(): SupportConnection = ToggleableSupportConnection()

        // The retry scheduler records sync-outcome metrics; the minimal TestApplication has no
        // actuator MeterRegistry, so provide a simple one (the real app gets it from actuator).
        @Bean
        @ConditionalOnMissingBean(MeterRegistry::class)
        fun testMeterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }

    @Autowired
    lateinit var syncPort: SupportRequestSyncPort

    @Autowired
    lateinit var pollScheduler: SupportRequestPollScheduler

    @Autowired
    lateinit var retryScheduler: SupportRequestSyncScheduler

    @Autowired
    lateinit var supportConnection: SupportConnection

    @Autowired
    lateinit var backgroundWork: SupportBackgroundWork

    @Autowired
    lateinit var jdbi: Jdbi

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    lateinit var roundLock: SupportRequestRoundLock

    private val recording get() = syncPort as RecordingSupportRequestSyncPort

    private val toggleableConnectivity get() = supportConnection as ToggleableSupportConnection

    private val testUserKey = UserKey.of("00000000-0000-0000-0000-feedbac00077")

    @BeforeEach
    fun setUp() {
        recording.reset()
        toggleableConnectivity.serviceReachable = true
        ensureUser(testUserKey, "support-request-driver-author", "support-request-driver@epistola.test", "Feedback Driver Author")
    }

    private fun consent(tenant: Tenant) = withMediator { GrantSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

    private fun createSupportRequest(tenant: Tenant, title: String = "Driver Bug"): SupportRequest = withMediator {
        val created = CreateSupportRequest(
            id = SupportRequestId(SupportRequestKey.generate(), TenantId(tenant.id)),
            title = title,
            description = "desc",
            category = SupportRequestCategory.BUG,
            priority = SupportRequestPriority.MEDIUM,
            sourceUrl = null,
            consoleLogs = null,
            metadata = null,
            createdBy = testUserKey,
        ).execute()
        backgroundWork.awaitIdle()
        GetSupportRequest(SupportRequestId(created.id, TenantId(tenant.id))).query()!!
    }

    private fun addScreenshot(supportRequest: SupportRequest, bytes: ByteArray = byteArrayOf(1, 2, 3, 4)): SupportRequestAsset = withMediator {
        AddSupportRequestAsset(
            id = SupportRequestAssetId(SupportRequestAssetKey.generate(), SupportRequestId(supportRequest.id, TenantId(supportRequest.tenantKey))),
            content = bytes,
            contentType = "image/jpeg",
            filename = "screenshot.jpeg",
        ).execute()
    }.also { backgroundWork.awaitIdle() }

    /** Consents for [tenant] first: the drivers send nothing of a tenant without consent. */
    private fun createSyncedSupportRequest(tenant: Tenant, title: String = "Driver Bug"): SupportRequest = withMediator {
        GrantSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute()
        val created = CreateSupportRequest(
            id = SupportRequestId(SupportRequestKey.generate(), TenantId(tenant.id)),
            title = title,
            description = "desc",
            category = SupportRequestCategory.BUG,
            priority = SupportRequestPriority.MEDIUM,
            sourceUrl = null,
            consoleLogs = null,
            metadata = null,
            createdBy = testUserKey,
        ).execute()
        // OnSupportRequestCreated started a sending round in the background; wait for it, then reload.
        backgroundWork.awaitIdle()
        GetSupportRequest(SupportRequestId(created.id, TenantId(tenant.id))).query()!!
    }

    @Test
    fun `with the configuration handing new requests to Epistola, a new request goes there and the desk says where that comes from`() {
        val handling = withMediator { GetSupportRequestHandling().query() }
        assertThat(handling.fromConfiguration).isTrue()
        assertThat(handling.connectionId).isEqualTo(withMediator { EnsureConfiguredSupportConnection().execute() })

        val supportRequest = createSyncedSupportRequest(createTenant("Driver Configured"))

        assertThat(supportRequest.handlerConnection).isEqualTo(withMediator { EnsureConfiguredSupportConnection().execute() })
        assertThat(recording.createdTickets.map { it.id }).contains(supportRequest.id)
    }

    @Test
    fun `creating support request pushes to the support service and stores the external ref`() {
        val tenant = createTenant("Driver Push")
        val supportRequest = createSyncedSupportRequest(tenant)

        assertThat(recording.createdTickets.map { it.id }).contains(supportRequest.id)
        assertThat(supportRequest.syncStatus).isEqualTo(SyncStatus.SYNCED)
        assertThat(supportRequest.externalRef).isEqualTo("hub-${supportRequest.id.value}")
    }

    @Test
    fun `adding a local comment pushes it and stores the external comment id`() {
        val tenant = createTenant("Driver Comment")
        val supportRequest = createSyncedSupportRequest(tenant)
        recording.addedComments.clear()

        withMediator {
            AddSupportRequestComment(
                id = SupportRequestCommentId(SupportRequestCommentKey.generate(), SupportRequestId(supportRequest.id, TenantId(tenant.id))),
                body = "Please fix",
                authorName = "Alice",
                authorEmail = "alice@acme.test",
            ).execute()
        }
        backgroundWork.awaitIdle()

        assertThat(recording.addedComments).hasSize(1)
        val stored = withMediator { GetSupportRequestComments(SupportRequestId(supportRequest.id, TenantId(tenant.id))).query() }
        assertThat(stored.single().externalCommentId).isNotNull()
    }

    @Test
    fun `an inbound synced comment is not pushed back out`() {
        val tenant = createTenant("Driver NoLoop")
        val supportRequest = createSyncedSupportRequest(tenant)
        recording.addedComments.clear()

        withMediator {
            SyncSupportRequestComment(
                tenantKey = tenant.id,
                supportRequestId = supportRequest.id,
                body = "from the hub",
                authorName = "Triager",
                authorEmail = null,
                externalCommentId = "ext-inbound-1",
                connectionId = withMediator { EnsureConfiguredSupportConnection().execute() },
            ).execute()
        }

        assertThat(recording.addedComments).isEmpty()
    }

    @Test
    fun `a local status change is pushed to the support service`() {
        val tenant = createTenant("Driver Status")
        val supportRequest = createSyncedSupportRequest(tenant)

        withMediator {
            UpdateSupportRequestStatus(
                id = SupportRequestId(supportRequest.id, TenantId(tenant.id)),
                status = SupportRequestStatus.CLOSED,
            ).execute()
        }
        // Sent by a round started in the background.
        backgroundWork.awaitIdle()

        assertThat(recording.statusUpdates.map { it.second }).contains(SupportRequestStatus.CLOSED)
    }

    @Test
    fun `an inbound synced status change is not pushed back out`() {
        val tenant = createTenant("Driver StatusNoLoop")
        val supportRequest = createSyncedSupportRequest(tenant)
        recording.statusUpdates.clear()

        withMediator {
            SyncSupportRequestStatus(
                id = SupportRequestId(supportRequest.id, TenantId(tenant.id)),
                status = SupportRequestStatus.CLOSED,
                connectionId = withMediator { EnsureConfiguredSupportConnection().execute() },
            ).execute()
        }

        assertThat(recording.statusUpdates).isEmpty()
    }

    @Test
    fun `polling applies inbound status and comment and advances the cursor`() {
        val tenant = createTenant("Driver Poll")
        val supportRequest = createSyncedSupportRequest(tenant)
        val ref = supportRequest.externalRef!!

        recording.pages.addLast(
            ExternalUpdatePage(
                updates = listOf(
                    ExternalUpdate.StatusChange(
                        seq = 4,
                        tenantKey = tenant.id,
                        externalRef = ref,
                        occurredAt = OCCURRED_AT,
                        newStatus = SupportRequestStatus.IN_PROGRESS,
                    ),
                ),
                lastSequence = 4,
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
                        occurredAt = OCCURRED_AT,
                        externalCommentId = "hub-c-1",
                        authorName = "Triager",
                        authorEmail = null,
                        body = "On it",
                    ),
                ),
                lastSequence = 9,
                hasMore = false,
            ),
        )

        pollScheduler.pollForUpdates()

        // Both pages drained in one run, each acknowledged by the next call, the last by a call that
        // found nothing more.
        assertThat(recording.fetchCalls).containsExactly(null, 4L, 9L)
        val updated = withMediator { GetSupportRequest(SupportRequestId(supportRequest.id, TenantId(tenant.id))).query()!! }
        assertThat(updated.status).isEqualTo(SupportRequestStatus.IN_PROGRESS)
        val comments = withMediator { GetSupportRequestComments(SupportRequestId(supportRequest.id, TenantId(tenant.id))).query() }
        assertThat(comments.map { it.externalCommentId }).contains("hub-c-1")
    }

    @Test
    fun `an empty poll acknowledges nothing and stops`() {
        pollScheduler.pollForUpdates()

        assertThat(recording.fetchCalls).containsExactly(null)
    }

    @Test
    fun `refused support request backs off, reads FAILED after the maximum, and is still sent once the service takes it`() {
        val tenant = createTenant("Driver Retry")
        consent(tenant)
        recording.failCreate = true

        // Create a PENDING item whose immediate push failed (failCreate swallowed in OnSupportRequestCreated).
        val supportRequestId = SupportRequestId(SupportRequestKey.generate(), TenantId(tenant.id))
        withMediator {
            CreateSupportRequest(
                id = supportRequestId,
                title = "Will fail",
                description = "desc",
                category = SupportRequestCategory.BUG,
                priority = SupportRequestPriority.LOW,
                sourceUrl = null,
                consoleLogs = null,
                metadata = null,
                createdBy = testUserKey,
            ).execute()
        }
        assertThat(withMediator { GetSupportRequest(supportRequestId).query()!! }.syncStatus).isEqualTo(SyncStatus.PENDING)

        // Each refusal counts an attempt and backs off; after MAX it reads FAILED, but is still tried.
        repeat(ListPendingSyncSupportRequest.MAX_SYNC_ATTEMPTS) {
            retryScheduler.retryPendingSync()
            testClock.advanceBy(Duration.ofHours(7))
        }

        assertThat(withMediator { GetSupportRequest(supportRequestId).query()!! }.syncStatus).isEqualTo(SyncStatus.FAILED)
        assertThat(withMediator { ListPendingSyncSupportRequest(limit = 10_000, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.map { it.id }).`as`("never given up").contains(supportRequestId.key)
        recording.failCreate = false
        retryScheduler.retryPendingSync()
        assertThat(withMediator { GetSupportRequest(supportRequestId).query()!! }.syncStatus).isEqualTo(SyncStatus.SYNCED)
    }

    @Test
    fun `retry sweep backs off and does not burn attempts while the support service is unreachable`() {
        val tenant = createTenant("Driver Backoff")
        consent(tenant)
        recording.failCreate = true

        // PENDING item whose immediate push failed.
        val supportRequestId = SupportRequestId(SupportRequestKey.generate(), TenantId(tenant.id))
        withMediator {
            CreateSupportRequest(
                id = supportRequestId,
                title = "Hub down",
                description = "desc",
                category = SupportRequestCategory.BUG,
                priority = SupportRequestPriority.LOW,
                sourceUrl = null,
                consoleLogs = null,
                metadata = null,
                createdBy = testUserKey,
            ).execute()
        }
        // The immediate push runs in the background: wait until it has failed before the hub goes away.
        backgroundWork.awaitIdle()
        assertThat(withMediator { GetSupportRequest(supportRequestId).query()!! }.syncStatus).isEqualTo(SyncStatus.PENDING)

        // Hub unreachable: even after many sweeps the item is never attempted, so it stays PENDING
        // (not FAILED) and the port is never called.
        toggleableConnectivity.serviceReachable = false
        recording.createdTickets.clear()
        repeat(ListPendingSyncSupportRequest.MAX_SYNC_ATTEMPTS + 2) { retryScheduler.retryPendingSync() }

        assertThat(recording.createdTickets).isEmpty()
        assertThat(withMediator { GetSupportRequest(supportRequestId).query()!! }.syncStatus).isEqualTo(SyncStatus.PENDING)
    }

    @Test
    fun `poll backs off and does not fetch while the support service is unreachable`() {
        toggleableConnectivity.serviceReachable = false

        pollScheduler.pollForUpdates()

        assertThat(recording.fetchCalls).isEmpty()
    }

    @Test
    fun `retry re-pushes a comment whose immediate push failed`() {
        val tenant = createTenant("Driver CommentRetry")
        val supportRequest = createSyncedSupportRequest(tenant)

        // Immediate comment push fails, leaving external_comment_id null.
        recording.failAddComment = true
        val commentId = SupportRequestCommentId(SupportRequestCommentKey.generate(), SupportRequestId(supportRequest.id, TenantId(tenant.id)))
        withMediator {
            AddSupportRequestComment(
                id = commentId,
                body = "retry me",
                authorName = "Bob",
                authorEmail = null,
            ).execute()
        }
        backgroundWork.awaitIdle()
        assertThat(withMediator { GetSupportRequestComments(SupportRequestId(supportRequest.id, TenantId(tenant.id))).query() }.single().externalCommentId).isNull()

        // Hub recovers; the retry sweep pushes it and stores the ref.
        recording.failAddComment = false
        recording.addedComments.clear()
        // The refusal backed the comment off; once that is over, the sweep sends it.
        testClock.advanceBy(Duration.ofMinutes(2))
        retryScheduler.retryPendingSync()

        assertThat(recording.addedComments).hasSize(1)
        assertThat(withMediator { GetSupportRequestComments(SupportRequestId(supportRequest.id, TenantId(tenant.id))).query() }.single().externalCommentId).isNotNull()
    }

    @Test
    fun `without consent support request stays local and nothing is sent`() {
        val tenant = createTenant("Driver NoConsent")

        val supportRequest = createSupportRequest(tenant)
        addScreenshot(supportRequest)
        retryScheduler.retryPendingSync()

        assertThat(supportRequest.syncStatus).isEqualTo(SyncStatus.NOT_CONFIGURED)
        assertThat(recording.createdTickets.map { it.id }).doesNotContain(supportRequest.id)
        assertThat(recording.uploadedAssets.map { it.supportRequest.id }).doesNotContain(supportRequest.id)
    }

    @Test
    fun `support request submitted before consent is not sent once consent is given`() {
        val tenant = createTenant("Driver Before")
        val before = createSupportRequest(tenant)

        consent(tenant)
        retryScheduler.retryPendingSync()

        assertThat(withMediator { GetSupportRequest(SupportRequestId(before.id, TenantId(tenant.id))).query()!! }.syncStatus).isEqualTo(SyncStatus.NOT_CONFIGURED)
        assertThat(recording.createdTickets.map { it.id }).doesNotContain(before.id)
    }

    @Test
    fun `after withdrawal comments, status changes and replies are no longer exchanged`() {
        val tenant = createTenant("Driver Withdrawn")
        val supportRequest = createSyncedSupportRequest(tenant)
        val supportRequestId = SupportRequestId(supportRequest.id, TenantId(tenant.id))
        withMediator { WithdrawSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        recording.addedComments.clear()
        recording.statusUpdates.clear()

        withMediator {
            AddSupportRequestComment(SupportRequestCommentId(SupportRequestCommentKey.generate(), supportRequestId), "after withdrawal", "Alice", null).execute()
            UpdateSupportRequestStatus(supportRequestId, SupportRequestStatus.CLOSED).execute()
        }
        retryScheduler.retryPendingSync()
        recording.pages.addLast(
            ExternalUpdatePage(
                updates = listOf(ExternalUpdate.StatusChange(3, tenant.id, supportRequest.externalRef!!, OCCURRED_AT, SupportRequestStatus.IN_PROGRESS)),
                lastSequence = 3,
                hasMore = false,
            ),
        )
        pollScheduler.pollForUpdates()

        assertThat(recording.addedComments).isEmpty()
        assertThat(recording.statusUpdates).isEmpty()
        assertThat(withMediator { GetSupportRequest(supportRequestId).query()!! }.status).`as`("the reply is not applied").isEqualTo(SupportRequestStatus.CLOSED)
        assertThat(recording.fetchCalls).`as`("but acknowledged").containsExactly(null, 3L)
    }

    @Test
    fun `after consent is given again shared support request carries on, but what was written while withdrawn is never sent`() {
        val tenant = createTenant("Driver Regrant")
        val supportRequest = createSyncedSupportRequest(tenant)
        val supportRequestId = SupportRequestId(supportRequest.id, TenantId(tenant.id))
        withMediator { WithdrawSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        val whileWithdrawn = SupportRequestCommentId(SupportRequestCommentKey.generate(), supportRequestId)
        withMediator { AddSupportRequestComment(whileWithdrawn, "written while withdrawn", "Alice", "alice@example.com").execute() }
        val screenshotWhileWithdrawn = addScreenshot(supportRequest)

        consent(tenant)
        recording.addedComments.clear()
        recording.uploadedAssets.clear()
        recording.statusUpdates.clear()
        retryScheduler.retryPendingSync()
        assertThat(recording.addedComments.filter { it.first.id == supportRequest.id }).`as`("written while withdrawn: never sent").isEmpty()
        assertThat(recording.uploadedAssets.filter { it.supportRequest.id == supportRequest.id }).`as`("added while withdrawn: never sent").isEmpty()

        val afterGrant = SupportRequestCommentId(SupportRequestCommentKey.generate(), supportRequestId)
        withMediator {
            AddSupportRequestComment(afterGrant, "written after the new grant", "Alice", null).execute()
            UpdateSupportRequestStatus(supportRequestId, SupportRequestStatus.CLOSED).execute()
        }
        backgroundWork.awaitIdle()
        val screenshotAfterGrant = addScreenshot(supportRequest)
        retryScheduler.retryPendingSync()

        assertThat(recording.addedComments.filter { it.first.id == supportRequest.id }.map { it.second.id }).containsExactly(afterGrant.key)
        assertThat(recording.statusUpdates.filter { it.first.id == supportRequest.id }.map { it.second }).containsExactly(SupportRequestStatus.CLOSED)
        assertThat(recording.uploadedAssets.filter { it.supportRequest.id == supportRequest.id }.map { it.assetKey }).containsExactly(screenshotAfterGrant.id)
        assertThat(withMediator { ListUnsyncedComments(limit = 10_000, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.map { it.id }).doesNotContain(whileWithdrawn.key)
        assertThat(withMediator { ListUnsyncedSupportRequestAssets(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.map { it.id }).doesNotContain(screenshotWhileWithdrawn.id.value)
    }

    @Test
    fun `withdrawing stops a comment and a screenshot not sent yet, also when consent is given again`() {
        val tenant = createTenant("Driver StopComment")
        val supportRequest = createSyncedSupportRequest(tenant)
        val supportRequestId = SupportRequestId(supportRequest.id, TenantId(tenant.id))
        recording.failAddComment = true
        recording.failUploadAsset = true
        val unsent = SupportRequestCommentId(SupportRequestCommentKey.generate(), supportRequestId)
        withMediator { AddSupportRequestComment(unsent, "not sent before the withdrawal", "Alice", null).execute() }
        val unsentScreenshot = addScreenshot(supportRequest)

        withMediator { WithdrawSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        consent(tenant)
        recording.failAddComment = false
        recording.failUploadAsset = false
        recording.addedComments.clear()
        recording.uploadedAssets.clear()
        retryScheduler.retryPendingSync()

        assertThat(recording.addedComments.map { it.second.id }).doesNotContain(unsent.key)
        assertThat(recording.uploadedAssets.map { it.assetKey }).doesNotContain(unsentScreenshot.id)
    }

    @Test
    fun `a comment the service refuses backs off, is kept locally, and is tried again later`() {
        val tenant = createTenant("Driver CommentRetry")
        val supportRequest = createSyncedSupportRequest(tenant)
        val supportRequestId = SupportRequestId(supportRequest.id, TenantId(tenant.id))
        recording.failAddComment = true
        val commentId = SupportRequestCommentId(SupportRequestCommentKey.generate(), supportRequestId)
        withMediator { AddSupportRequestComment(commentId, "refused every time", "Alice", null).execute() }

        backgroundWork.awaitIdle()
        retryScheduler.retryPendingSync()

        assertThat(withMediator { ListUnsyncedComments(limit = 10_000, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.map { it.id }).`as`("backing off").doesNotContain(commentId.key)
        assertThat(withMediator { GetSupportRequestComments(supportRequestId).query() }.map { it.id }).`as`("kept locally").contains(commentId.key)
        testClock.advanceBy(Duration.ofHours(7))
        assertThat(withMediator { ListUnsyncedComments(limit = 10_000, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.map { it.id }).`as`("tried again").contains(commentId.key)
    }

    @Test
    fun `withdrawing stops support request waiting to be sent, also when consent is given again`() {
        val tenant = createTenant("Driver Stop")
        consent(tenant)
        recording.failCreate = true
        val pending = createSupportRequest(tenant)
        assertThat(pending.syncStatus).isEqualTo(SyncStatus.PENDING)

        val withdrawal = withMediator { WithdrawSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        consent(tenant)
        recording.failCreate = false
        recording.createdTickets.clear()
        retryScheduler.retryPendingSync()

        assertThat(withdrawal.stoppedSupportRequest).isEqualTo(1)
        assertThat(withMediator { GetSupportRequest(SupportRequestId(pending.id, TenantId(tenant.id))).query()!! }.syncStatus).isEqualTo(SyncStatus.NOT_CONFIGURED)
        assertThat(recording.createdTickets.map { it.id }).doesNotContain(pending.id)
    }

    @Test
    fun `the screenshot stored after the support request is sent too`() {
        // SupportRequestHandler.create runs CreateSupportRequest (which sends the feedback at once) and only
        // then stores the screenshot; the screenshot must still reach the support service.
        val tenant = createTenant("Driver Screenshot")
        val supportRequest = createSyncedSupportRequest(tenant)
        assertThat(supportRequest.syncStatus).isEqualTo(SyncStatus.SYNCED)

        val asset = addScreenshot(supportRequest, byteArrayOf(9, 8, 7))

        val uploaded = recording.uploadedAssets.single { it.supportRequest.id == supportRequest.id }
        assertThat(uploaded.assetKey).isEqualTo(asset.id)
        assertThat(uploaded.content.content).containsExactly(9, 8, 7)
        assertThat(uploaded.content.contentType).isEqualTo("image/jpeg")
        assertThat(uploaded.supportRequest.externalRef).isEqualTo(supportRequest.externalRef)
        assertThat(withMediator { ListUnsyncedSupportRequestAssets(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.map { it.id }).doesNotContain(asset.id.value)
        recording.uploadedAssets.clear()
        retryScheduler.retryPendingSync()
        assertThat(recording.uploadedAssets).`as`("sent once").isEmpty()
    }

    @Test
    fun `the retry sweep sends the screenshot of support request that reached the service later`() {
        val tenant = createTenant("Driver LateAsset")
        consent(tenant)
        recording.failCreate = true
        val supportRequest = createSupportRequest(tenant)
        val asset = addScreenshot(supportRequest)
        assertThat(recording.uploadedAssets).isEmpty()

        recording.failCreate = false
        // The refusal backed it off; once that is over, the sweep sends it and then its screenshot.
        testClock.advanceBy(Duration.ofMinutes(2))
        retryScheduler.retryPendingSync()

        assertThat(withMediator { GetSupportRequest(SupportRequestId(supportRequest.id, TenantId(tenant.id))).query()!! }.syncStatus).isEqualTo(SyncStatus.SYNCED)
        assertThat(recording.uploadedAssets.filter { it.supportRequest.id == supportRequest.id }.map { it.assetKey }).containsExactly(asset.id)
    }

    @Test
    fun `an asset is retried without counting attempts while the service is down, and backs off when refused`() {
        val tenant = createTenant("Driver AssetRetry")
        val supportRequest = createSyncedSupportRequest(tenant)
        recording.assetServiceUnavailable = true
        val asset = addScreenshot(supportRequest)
        backgroundWork.awaitIdle()
        repeat(6) { retryScheduler.retryPendingSync() }
        assertThat(withMediator { ListUnsyncedSupportRequestAssets(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.map { it.id }).`as`("still waiting").contains(asset.id.value)

        recording.assetServiceUnavailable = false
        recording.failUploadAsset = true
        retryScheduler.retryPendingSync()

        assertThat(withMediator { ListUnsyncedSupportRequestAssets(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.map { it.id }).`as`("backing off").doesNotContain(asset.id.value)
        testClock.advanceBy(Duration.ofHours(7))
        assertThat(withMediator { ListUnsyncedSupportRequestAssets(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.map { it.id }).`as`("tried again").contains(asset.id.value)
        assertThat(withMediator { ListSupportRequestAssets(SupportRequestId(supportRequest.id, TenantId(tenant.id))).query() }.map { it.id }).`as`("kept locally").contains(asset.id)
        assertThat(recording.uploadedAssets.filter { it.supportRequest.id == supportRequest.id }).isEmpty()
    }

    @Test
    fun `nothing is sent while the support request service is not granted`() {
        val tenant = createTenant("Driver NotReady")
        recording.ready = false
        val supportRequest = createSyncedSupportRequest(tenant)
        repeat(ListPendingSyncSupportRequest.MAX_SYNC_ATTEMPTS + 1) { retryScheduler.retryPendingSync() }
        pollScheduler.pollForUpdates()

        assertThat(supportRequest.syncStatus).`as`("waits, not failed").isEqualTo(SyncStatus.PENDING)
        assertThat(recording.createdTickets).isEmpty()
        assertThat(recording.fetchCalls).isEmpty()

        recording.ready = true
        retryScheduler.retryPendingSync()
        assertThat(recording.createdTickets.filter { it.tenantKey == tenant.id }.map { it.id }).containsExactly(supportRequest.id)
    }

    @Test
    fun `an update that fails to apply is not acknowledged, only those applied before it`() {
        val tenant = createTenant("Driver PollFailure")
        val supportRequest = createSyncedSupportRequest(tenant)
        val ref = supportRequest.externalRef!!
        recording.pages.addLast(
            ExternalUpdatePage(
                updates = listOf(
                    ExternalUpdate.StatusChange(21, tenant.id, ref, OCCURRED_AT, SupportRequestStatus.IN_PROGRESS),
                    // A body the database refuses (a NUL character): applying it fails.
                    ExternalUpdate.Comment(22, tenant.id, ref, OCCURRED_AT, "ext-nul", "Triager", null, "bad\u0000body"),
                ),
                lastSequence = 22,
                hasMore = false,
            ),
        )

        pollScheduler.pollForUpdates()

        assertThat(withMediator { GetSupportRequest(SupportRequestId(supportRequest.id, TenantId(tenant.id))).query()!! }.status)
            .isEqualTo(SupportRequestStatus.IN_PROGRESS)
        assertThat(recording.fetchCalls).`as`("the applied update is acknowledged, the failed one comes again").containsExactly(null, 21L)
    }

    @Test
    fun `a page that says nothing more follows is the last call of the run, also when it names a position`() {
        repeat(5) { recording.pages.addLast(ExternalUpdatePage(updates = emptyList(), lastSequence = 43, hasMore = false)) }

        pollScheduler.pollForUpdates()

        assertThat(recording.fetchCalls).`as`("no spinning on an empty page").containsExactly(null, 43L)
    }

    @Test
    fun `a run that stops at its page limit acknowledges what it handled before it goes`() {
        repeat(SupportRequestPollScheduler.MAX_PAGES_PER_RUN + 1) { i ->
            recording.pages.addLast(ExternalUpdatePage(updates = emptyList(), lastSequence = (i + 1).toLong(), hasMore = true))
        }

        pollScheduler.pollForUpdates()

        assertThat(recording.fetchCalls.last()).`as`("the last handled page is acknowledged in this run")
            .isEqualTo(SupportRequestPollScheduler.MAX_PAGES_PER_RUN.toLong())
    }

    @Test
    fun `a status from the support service settles a local one still waiting, which is then not sent`() {
        val tenant = createTenant("Driver Status Settled")
        val supportRequest = createSyncedSupportRequest(tenant)
        val supportRequestId = SupportRequestId(supportRequest.id, TenantId(tenant.id))
        recording.failUpdateStatus = true
        // The change is sent in the background, here later than the command returns.
        recording.beforeSend = { outbound, _ -> if (outbound == RecordingSupportRequestSyncPort.Outbound.STATUS) Thread.sleep(300) }
        withMediator { UpdateSupportRequestStatus(supportRequestId, SupportRequestStatus.CLOSED).execute() }
        // Waited for while sending still fails, so the status stays waiting to be sent.
        backgroundWork.awaitIdle()
        recording.failUpdateStatus = false
        recording.statusUpdates.clear()
        recording.pages.addLast(
            ExternalUpdatePage(
                updates = listOf(ExternalUpdate.StatusChange(5, tenant.id, supportRequest.externalRef!!, OCCURRED_AT, SupportRequestStatus.RESOLVED)),
                lastSequence = 5,
                hasMore = false,
            ),
        )
        pollScheduler.pollForUpdates()

        retryScheduler.retryPendingSync()

        assertThat(withMediator { GetSupportRequest(supportRequestId).query()!! }.status).isEqualTo(SupportRequestStatus.RESOLVED)
        assertThat(recording.statusUpdates).`as`("Epistola's own status is not sent back as the suite's").isEmpty()
    }

    @Test
    fun `setting the status a request already has changes nothing and sends nothing`() {
        val tenant = createTenant("Driver Same Status")
        val supportRequest = createSyncedSupportRequest(tenant)
        val supportRequestId = SupportRequestId(supportRequest.id, TenantId(tenant.id))
        withMediator { UpdateSupportRequestStatus(supportRequestId, SupportRequestStatus.CLOSED).execute() }
        retryScheduler.retryPendingSync()
        recording.statusUpdates.clear()

        val changed = withMediator { UpdateSupportRequestStatus(supportRequestId, SupportRequestStatus.CLOSED).execute() }
        retryScheduler.retryPendingSync()

        assertThat(changed).isFalse()
        assertThat(recording.statusUpdates).isEmpty()
    }

    @Test
    fun `a page of updates this suite skipped is still acknowledged`() {
        recording.pages.addLast(ExternalUpdatePage(updates = emptyList(), lastSequence = 12, hasMore = false))

        pollScheduler.pollForUpdates()

        assertThat(recording.fetchCalls).containsExactly(null, 12L)
    }

    @Test
    fun `a status changed while consent is withdrawn is never sent, also once consent is given again`() {
        val tenant = createTenant("Driver StatusWithdrawn")
        val supportRequest = createSyncedSupportRequest(tenant)
        val supportRequestId = SupportRequestId(supportRequest.id, TenantId(tenant.id))
        withMediator { WithdrawSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        withMediator { UpdateSupportRequestStatus(supportRequestId, SupportRequestStatus.CLOSED).execute() }
        backgroundWork.awaitIdle()

        consent(tenant)
        retryScheduler.retryPendingSync()

        assertThat(recording.statusUpdates.filter { it.first.id == supportRequest.id }).`as`("changed while withdrawn: never sent").isEmpty()
        assertThat(withMediator { ListUnsentSupportRequestStatuses(limit = 10_000, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.map { it.supportRequest.id }).doesNotContain(supportRequest.id)

        withMediator { UpdateSupportRequestStatus(supportRequestId, SupportRequestStatus.OPEN).execute() }
        backgroundWork.awaitIdle()
        assertThat(recording.statusUpdates.filter { it.first.id == supportRequest.id }.map { it.second }).`as`("changed after the new grant: sent").containsExactly(SupportRequestStatus.OPEN)
    }

    @Test
    fun `withdrawing stops a status change not sent yet, also when consent is given again`() {
        val tenant = createTenant("Driver StopStatus")
        val supportRequest = createSyncedSupportRequest(tenant)
        val supportRequestId = SupportRequestId(supportRequest.id, TenantId(tenant.id))
        // The service does not take feedback now: the change waits, unsent.
        recording.ready = false
        withMediator { UpdateSupportRequestStatus(supportRequestId, SupportRequestStatus.CLOSED).execute() }
        backgroundWork.awaitIdle()

        withMediator { WithdrawSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        consent(tenant)
        recording.ready = true
        retryScheduler.retryPendingSync()

        assertThat(recording.statusUpdates.filter { it.first.id == supportRequest.id }).isEmpty()
    }

    @Test
    fun `withdrawing stops support request the service refused until it read FAILED, also when consent is given again`() {
        val tenant = createTenant("Driver StopFailed")
        consent(tenant)
        recording.failCreate = true
        val failed = createSupportRequest(tenant)
        val failedId = SupportRequestId(failed.id, TenantId(tenant.id))
        repeat(ListPendingSyncSupportRequest.MAX_SYNC_ATTEMPTS) {
            retryScheduler.retryPendingSync()
            testClock.advanceBy(Duration.ofHours(7))
        }
        assertThat(withMediator { GetSupportRequest(failedId).query()!! }.syncStatus).isEqualTo(SyncStatus.FAILED)

        val withdrawal = withMediator { WithdrawSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        consent(tenant)
        recording.failCreate = false
        recording.createdTickets.clear()
        testClock.advanceBy(Duration.ofHours(7))
        retryScheduler.retryPendingSync()

        assertThat(withdrawal.stoppedSupportRequest).isEqualTo(1)
        assertThat(recording.createdTickets.map { it.id }).doesNotContain(failed.id)
        assertThat(withMediator { GetSupportRequest(failedId).query()!! }.syncStatus).isEqualTo(SyncStatus.NOT_CONFIGURED)
    }

    @Test
    fun `support request submitted while consent is withdrawn is never sent, also once consent is given again`() {
        val tenant = createTenant("Driver SubmitWithdrawn")
        consent(tenant)
        withMediator { WithdrawSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        val supportRequest = createSupportRequest(tenant)

        consent(tenant)
        retryScheduler.retryPendingSync()

        assertThat(supportRequest.syncStatus).isEqualTo(SyncStatus.NOT_CONFIGURED)
        assertThat(recording.createdTickets.map { it.id }).doesNotContain(supportRequest.id)
    }

    /**
     * A withdrawal that lands while a round is sending: the call on its way completes (Epistola then
     * holds that item), but nothing of the tenant is started after it, of every kind the round sends.
     */
    @ParameterizedTest
    @EnumSource(RecordingSupportRequestSyncPort.Outbound::class)
    fun `a withdrawal during a sending round stops the rest of the round`(kind: RecordingSupportRequestSyncPort.Outbound) {
        val tenant = createTenant("Driver MidRound $kind")
        val first = createSyncedSupportRequest(tenant, "first")
        val second = createSyncedSupportRequest(tenant, "second")
        val firstId = SupportRequestId(first.id, TenantId(tenant.id))
        val secondId = SupportRequestId(second.id, TenantId(tenant.id))
        // Two items of [kind] wait: the service does not take feedback while they are made.
        recording.ready = false
        when (kind) {
            RecordingSupportRequestSyncPort.Outbound.SUPPORT_REQUEST -> repeat(2) { createSupportRequest(tenant, "waiting $it") }

            RecordingSupportRequestSyncPort.Outbound.ASSET -> repeat(2) { addScreenshot(first) }

            RecordingSupportRequestSyncPort.Outbound.COMMENT -> withMediator {
                repeat(2) { AddSupportRequestComment(SupportRequestCommentId(SupportRequestCommentKey.generate(), firstId), "waiting $it", "Alice", null).execute() }
            }

            RecordingSupportRequestSyncPort.Outbound.STATUS -> withMediator {
                UpdateSupportRequestStatus(firstId, SupportRequestStatus.CLOSED).execute()
                UpdateSupportRequestStatus(secondId, SupportRequestStatus.CLOSED).execute()
            }
        }
        backgroundWork.awaitIdle()
        val sent = mutableListOf<RecordingSupportRequestSyncPort.Outbound>()
        recording.beforeSend = { outbound, supportRequest ->
            if (supportRequest.tenantKey == tenant.id) {
                sent += outbound
                if (outbound == kind && sent.count { it == kind } == 1) withMediator { WithdrawSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
            }
        }
        recording.ready = true

        retryScheduler.retryPendingSync()

        assertThat(sent).`as`("only the call on its way when consent was withdrawn").containsExactly(kind)
    }

    /**
     * A write that lands while a withdrawal is under way: the withdrawal's transaction has marked
     * consent withdrawn but not committed when the write runs. Racing two threads and hoping they
     * collide proves nothing, so the withdrawal runs in a transaction this test holds open. However
     * the two land, what the write made is either stopped by the withdrawal or written after it, and
     * is never sent once consent is given again.
     */
    @ParameterizedTest
    @EnumSource(RecordingSupportRequestSyncPort.Outbound::class)
    fun `a change written while a withdrawal is under way is never sent, also once consent is given again`(kind: RecordingSupportRequestSyncPort.Outbound) {
        val tenant = createTenant("Driver WithdrawRace $kind")
        val supportRequest = createSyncedSupportRequest(tenant)
        val supportRequestId = SupportRequestId(supportRequest.id, TenantId(tenant.id))
        // The immediate rounds do not send: what is left is up to the sweep after the new grant.
        recording.ready = false
        val withdrawing = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val holder = Executors.newSingleThreadExecutor()
        val writer = Executors.newSingleThreadExecutor()
        var written: UUID? = null
        try {
            val withdrawal = holder.submit(
                withMediator {
                    MediatorContext.runnable(mediator) {
                        TransactionTemplate(transactionManager).executeWithoutResult {
                            WithdrawSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute()
                            withdrawing.countDown()
                            letGo.await(20, TimeUnit.SECONDS)
                        }
                    }
                },
            )
            assertThat(withdrawing.await(20, TimeUnit.SECONDS)).isTrue()
            val write = writer.submit(
                withMediator {
                    MediatorContext.callable(mediator) {
                        when (kind) {
                            RecordingSupportRequestSyncPort.Outbound.SUPPORT_REQUEST -> CreateSupportRequest(
                                id = SupportRequestId(SupportRequestKey.generate(), TenantId(tenant.id)),
                                title = "racing",
                                description = "desc",
                                category = SupportRequestCategory.BUG,
                                priority = SupportRequestPriority.MEDIUM,
                                sourceUrl = null,
                                consoleLogs = null,
                                metadata = null,
                                createdBy = testUserKey,
                            ).execute().id.value

                            RecordingSupportRequestSyncPort.Outbound.ASSET -> AddSupportRequestAsset(
                                id = SupportRequestAssetId(SupportRequestAssetKey.generate(), supportRequestId),
                                content = byteArrayOf(9, 9, 9),
                                contentType = "image/jpeg",
                                filename = "screenshot.jpeg",
                            ).execute().id.value

                            RecordingSupportRequestSyncPort.Outbound.COMMENT ->
                                AddSupportRequestComment(SupportRequestCommentId(SupportRequestCommentKey.generate(), supportRequestId), "racing", "Alice", null).execute().id.value

                            RecordingSupportRequestSyncPort.Outbound.STATUS -> {
                                UpdateSupportRequestStatus(supportRequestId, SupportRequestStatus.CLOSED).execute()
                                supportRequest.id.value
                            }
                        }
                    }
                },
            )
            // Give the write time to land while the withdrawal is still open (or to wait for it).
            runCatching { write.get(1, TimeUnit.SECONDS) }
            letGo.countDown()
            withdrawal.get(20, TimeUnit.SECONDS)
            written = write.get(20, TimeUnit.SECONDS)
        } finally {
            letGo.countDown()
            holder.shutdown()
            writer.shutdown()
        }
        backgroundWork.awaitIdle()

        consent(tenant)
        recording.ready = true
        retryScheduler.retryPendingSync()

        when (kind) {
            RecordingSupportRequestSyncPort.Outbound.SUPPORT_REQUEST -> assertThat(recording.createdTickets.map { it.id.value }).doesNotContain(written)
            RecordingSupportRequestSyncPort.Outbound.ASSET -> assertThat(recording.uploadedAssets.map { it.assetKey.value }).doesNotContain(written)
            RecordingSupportRequestSyncPort.Outbound.COMMENT -> assertThat(recording.addedComments.map { it.second.id.value }).doesNotContain(written)
            RecordingSupportRequestSyncPort.Outbound.STATUS -> assertThat(recording.statusUpdates.filter { it.first.id == supportRequest.id }).isEmpty()
        }
    }

    /**
     * The `feedback` service deselected (or its grant withdrawn) while a round works through its batch:
     * the port is no longer ready (`RestSupportRequestSyncAdapter.isReady` is `hasScope(FEEDBACK)`, which
     * needs the selection). The call on its way completes; nothing is started after it, of every kind.
     */
    @ParameterizedTest
    @EnumSource(RecordingSupportRequestSyncPort.Outbound::class)
    fun `a service deselected during a sending round stops the rest of the round`(kind: RecordingSupportRequestSyncPort.Outbound) {
        val tenant = createTenant("Driver Deselect $kind")
        val first = createSyncedSupportRequest(tenant, "first")
        val second = createSyncedSupportRequest(tenant, "second")
        val firstId = SupportRequestId(first.id, TenantId(tenant.id))
        val secondId = SupportRequestId(second.id, TenantId(tenant.id))
        recording.ready = false
        when (kind) {
            RecordingSupportRequestSyncPort.Outbound.SUPPORT_REQUEST -> repeat(2) { createSupportRequest(tenant, "waiting $it") }

            RecordingSupportRequestSyncPort.Outbound.ASSET -> repeat(2) { addScreenshot(first) }

            RecordingSupportRequestSyncPort.Outbound.COMMENT -> withMediator {
                repeat(2) { AddSupportRequestComment(SupportRequestCommentId(SupportRequestCommentKey.generate(), firstId), "waiting $it", "Alice", null).execute() }
            }

            RecordingSupportRequestSyncPort.Outbound.STATUS -> withMediator {
                UpdateSupportRequestStatus(firstId, SupportRequestStatus.CLOSED).execute()
                UpdateSupportRequestStatus(secondId, SupportRequestStatus.CLOSED).execute()
            }
        }
        backgroundWork.awaitIdle()
        val sent = mutableListOf<RecordingSupportRequestSyncPort.Outbound>()
        recording.beforeSend = { outbound, supportRequest ->
            if (supportRequest.tenantKey == tenant.id) {
                sent += outbound
                if (outbound == kind && sent.count { it == kind } == 1) recording.ready = false
            }
        }
        recording.ready = true

        retryScheduler.retryPendingSync()

        assertThat(sent).`as`("only the call on its way when the service was deselected").containsExactly(kind)
    }

    @Test
    fun `a status changed while consent is withdrawn and the support request on its way is never sent`() {
        val tenant = createTenant("Driver InFlightStatus")
        consent(tenant)
        recording.ready = false
        val supportRequest = createSupportRequest(tenant)
        val supportRequestId = SupportRequestId(supportRequest.id, TenantId(tenant.id))
        recording.beforeSend = { outbound, sending ->
            if (outbound == RecordingSupportRequestSyncPort.Outbound.SUPPORT_REQUEST && sending.id == supportRequest.id) {
                withMediator {
                    WithdrawSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute()
                    UpdateSupportRequestStatus(supportRequestId, SupportRequestStatus.CLOSED).execute()
                }
            }
        }
        recording.ready = true
        retryScheduler.retryPendingSync()
        backgroundWork.awaitIdle()
        assertThat(recording.createdTickets.map { it.id }).`as`("on its way when consent was withdrawn").contains(supportRequest.id)
        recording.beforeSend = null

        consent(tenant)
        retryScheduler.retryPendingSync()

        assertThat(recording.statusUpdates.filter { it.first.id == supportRequest.id }).isEmpty()
    }

    /**
     * The call on its way at the withdrawal fails (refused, or a server error on that item): Epistola
     * holds nothing, and the failure must not bring the feedback back to waiting.
     */
    @ParameterizedTest
    @ValueSource(strings = ["refused", "server error"])
    fun `support request whose call on its way at the withdrawal fails is never sent, also once consent is given again`(failure: String) {
        val tenant = createTenant("Driver InFlightFails $failure")
        consent(tenant)
        recording.ready = false
        val supportRequest = createSupportRequest(tenant)
        val supportRequestId = SupportRequestId(supportRequest.id, TenantId(tenant.id))
        recording.beforeSend = { outbound, sending ->
            if (outbound == RecordingSupportRequestSyncPort.Outbound.SUPPORT_REQUEST && sending.id == supportRequest.id) {
                withMediator { WithdrawSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
                if (failure == "refused") {
                    recording.failCreate = true
                } else {
                    throw HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "simulated: 500 on this item", HttpHeaders.EMPTY, ByteArray(0), null)
                }
            }
        }
        recording.ready = true
        retryScheduler.retryPendingSync()
        recording.beforeSend = null
        recording.failCreate = false
        assertThat(withMediator { GetSupportRequest(supportRequestId).query()!! }.syncStatus).`as`("stays stopped").isEqualTo(SyncStatus.NOT_CONFIGURED)

        consent(tenant)
        recording.createdTickets.clear()
        testClock.advanceBy(Duration.ofHours(7))
        retryScheduler.retryPendingSync()

        assertThat(recording.createdTickets.map { it.id }).`as`("stopped by the withdrawal: never sent").doesNotContain(supportRequest.id)
    }

    /**
     * Another node's round holds the lock, and ends with the scope withdrawn or the service found
     * down; this node's round, waiting for the lock, must not send with what it read before it waited.
     */
    @ParameterizedTest
    @ValueSource(strings = ["scope withdrawn", "service down"])
    fun `a round that waited for another node's round checks again before it sends`(meanwhile: String) {
        val tenant = createTenant("Driver WaitedRound $meanwhile")
        consent(tenant)
        recording.ready = false
        val supportRequest = createSupportRequest(tenant)
        recording.ready = true
        val letGo = CountDownLatch(1)
        val holding = CountDownLatch(1)
        val otherNode = Thread.ofVirtual().start {
            roundLock.runExclusively(Duration.ofSeconds(30)) {
                holding.countDown()
                letGo.await(30, TimeUnit.SECONDS)
            }
        }
        assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue()
        val waiting = Thread.ofVirtual().start { retryScheduler.retryPendingSync() }
        waiting.join(Duration.ofMillis(600)) // past the gate, polling for the lock

        if (meanwhile == "scope withdrawn") recording.ready = false else toggleableConnectivity.serviceReachable = false
        letGo.countDown()
        otherNode.join(Duration.ofSeconds(10))
        waiting.join(Duration.ofSeconds(10))

        assertThat(waiting.isAlive).isFalse()
        assertThat(recording.createdTickets.map { it.id }).`as`("nothing sent after the wait").doesNotContain(supportRequest.id)
    }

    @Test
    fun `while the support service is unreachable the sharing page says consented support request waits, not that it is sent`() {
        val tenant = createTenant("Driver SharingOutage")
        consent(tenant)
        toggleableConnectivity.serviceReachable = false

        val settings = withMediator { GetSupportRequestSharingSettings(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }

        assertThat(settings.sendsSupportRequest).`as`("the gate sends nothing now").isFalse()
        assertThat(settings.consentedButWaiting).isTrue()
        toggleableConnectivity.serviceReachable = true
        assertThat(withMediator { GetSupportRequestSharingSettings(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.sendsSupportRequest).isTrue()
    }

    @Test
    fun `replies skipped while consent was withdrawn stay skipped, and later ones are applied once it is given again`() {
        val tenant = createTenant("Driver PollRegrant")
        val supportRequest = createSyncedSupportRequest(tenant)
        val supportRequestId = SupportRequestId(supportRequest.id, TenantId(tenant.id))
        val ref = supportRequest.externalRef!!
        withMediator { WithdrawSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        recording.pages.addLast(
            ExternalUpdatePage(
                updates = listOf(ExternalUpdate.Comment(3, tenant.id, ref, OCCURRED_AT, "ext-while-withdrawn", "Triager", null, "while withdrawn")),
                lastSequence = 3,
                hasMore = false,
            ),
        )
        pollScheduler.pollForUpdates()

        consent(tenant)
        recording.pages.addLast(
            ExternalUpdatePage(
                updates = listOf(ExternalUpdate.Comment(5, tenant.id, ref, OCCURRED_AT, "ext-after-grant", "Triager", null, "after the new grant")),
                lastSequence = 5,
                hasMore = false,
            ),
        )
        pollScheduler.pollForUpdates()

        assertThat(recording.fetchCalls).containsExactly(null, 3L, null, 5L)
        assertThat(withMediator { GetSupportRequestComments(supportRequestId).query() }.map { it.externalCommentId }).containsExactly("ext-after-grant")
    }

    companion object {
        private val OCCURRED_AT: Instant = Instant.parse("2026-11-05T12:00:00Z")
    }
}

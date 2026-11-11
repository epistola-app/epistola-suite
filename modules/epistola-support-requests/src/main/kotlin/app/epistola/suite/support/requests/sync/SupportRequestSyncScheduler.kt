// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.sync

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.common.ids.SupportRequestCommentId
import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.support.connection.SupportBackgroundWork
import app.epistola.suite.support.connection.SupportCluster
import app.epistola.suite.support.connection.SupportConnectionStore
import app.epistola.suite.support.requests.SupportRequest
import app.epistola.suite.support.requests.SyncStatus
import app.epistola.suite.support.requests.commands.MarkSupportRequestAssetSynced
import app.epistola.suite.support.requests.commands.RecordSupportRequestAssetSyncFailure
import app.epistola.suite.support.requests.commands.RecordSupportRequestCommentSyncFailure
import app.epistola.suite.support.requests.commands.RecordSupportRequestSyncFailure
import app.epistola.suite.support.requests.commands.UpdateSupportRequestCommentExternalRef
import app.epistola.suite.support.requests.commands.UpdateSupportRequestSyncRef
import app.epistola.suite.support.requests.queries.GetSupportRequest
import app.epistola.suite.support.requests.queries.GetSupportRequestAssetContent
import app.epistola.suite.support.requests.queries.GetSupportRequestDelivered
import app.epistola.suite.support.requests.queries.ListPendingSyncSupportRequest
import app.epistola.suite.support.requests.queries.ListUnsyncedComments
import app.epistola.suite.support.requests.queries.ListUnsyncedSupportRequestAssets
import app.epistola.suite.time.EpistolaClock
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Sends what is waiting for Epistola support: feedback still PENDING (or FAILED: nothing is given up
 * for good), assets and local comments of feedback already sent, and statuses changed here. The
 * event handlers start a round right after a change ([SupportRequestSyncKick]); this task runs one every
 * `retry-interval-ms` for whatever is left. Only tenants whose consent is in force are read (the
 * queries filter in SQL), and each item is read again right before it is sent
 * ([IsSupportRequestItemStillToSend]), so a withdrawal during a round stops the rest of it.
 *
 * Reliability (`docs/support-protocol.md`, "Reliability"):
 * - a round runs on [SupportBackgroundWork], never on the scheduler thread or a request thread, and
 *   one at a time in the cluster ([SupportRequestRoundLock]): a node waits for another node's round;
 * - it spends at most `round-budget-ms` and leaves the rest for the next round;
 * - "not now" for everything (unreachable, a deadline, `503`, `429`, no scope) ends the round with
 *   nothing counted; a `500` on one item lets it wait a minute, uncounted, and the round goes on
 *   (stopping after [SupportRequestSyncBackoff.MAX_ITEM_SERVER_ERRORS_IN_A_ROW] in a row); a refusal counts
 *   an attempt and backs the item off ([SupportRequestSyncBackoff]).
 *
 * Only active when `epistola.supportRequest.sync.enabled` is true, and only does work when the support
 * connection takes feedback ([SupportRequestSyncGate.canSendNow]).
 */
@Component
@ConditionalOnProperty(
    name = ["epistola.support.requests.sync.enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class SupportRequestSyncScheduler(
    private val ports: SupportRequestSyncPorts,
    private val gate: SupportRequestSyncGate,
    private val mediator: Mediator,
    private val properties: SupportRequestSyncProperties,
    private val meterRegistry: MeterRegistry,
    private val work: SupportBackgroundWork,
    private val roundLock: SupportRequestRoundLock,
) : ClusterScheduledTaskHandler {
    private val log = LoggerFactory.getLogger(javaClass)
    override val taskType: String = TASK_TYPE

    @Bean
    fun supportRequestRetryScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.FixedDelay(properties.retryIntervalMs),
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
        // Never owned by a node whose clock is off, which could not send: the other nodes run it.
        requiredCapability = SupportCluster.CAPABILITY,
    )

    /** Hands the round to [SupportBackgroundWork]: the scheduler thread never waits on the support service. */
    override fun handle(task: ClusterScheduledTask) {
        work.launch(WORK_KEY, again = true) { retryPendingSync() }
    }

    /** One round, on the calling thread. */
    fun retryPendingSync() {
        MediatorContext.runWithMediator(mediator) {
            // No connection takes support requests now (not wired, not granted, known unreachable): skip the whole round.
            if (ports.all().none { (id, _) -> gate.canSendNow(id) }) {
                log.debug("Feedback sync cannot send now; skipping the retry sweep")
                return@runWithMediator
            }
            // One node at a time: a round elsewhere sends what this one would. Waits for it (its budget
            // plus the call under way) rather than skipping, so what changed here is not left for the sweep.
            val ran = roundLock.runExclusively(Duration.ofMillis(2 * properties.roundBudgetMs)) {
                val round = Round(System.nanoTime() + properties.roundBudgetMs * 1_000_000)
                // Every connection in turn, within one budget. Again, after the wait: the round before
                // may have ended with a scope refused or a connection found down.
                for ((connectionId, port) in ports.all()) {
                    if (!gate.canSendNow(connectionId)) continue
                    round.connectionId = connectionId
                    // Requests first, so their assets and comments can follow in the same round.
                    val goOn = retryPendingSupportRequest(round, port) && retryPendingAssets(round, port) && retryPendingComments(round, port) && retryUnsentStatuses(round, port)
                    if (!goOn && round.spent()) break
                }
            }
            if (!ran) log.debug("Feedback sync round still running on another node; the next sweep sends what is left")
        }
    }

    /** One round's budget and its server errors in a row. */
    private inner class Round(private val deadline: Long) {
        private var serverErrors = 0

        /** The connection this part of the round sends to. */
        var connectionId: java.util.UUID = SupportConnectionStore.NO_CONNECTION
            set(value) {
                field = value
                serverErrors = 0
            }

        /** The budget is spent: no connection goes on. */
        fun spent(): Boolean = System.nanoTime() >= deadline

        /**
         * Asked before every item. False once the budget is spent, and once the connection no longer
         * takes feedback ([SupportRequestSyncGate.canSendNow]): an installation administrator deselected
         * the service (on any node: this node sees it within a second), Epistola withdrew it, or the
         * service was found down. The rest waits for the next round; nothing more is sent.
         */
        fun mayGoOn(): Boolean {
            if (System.nanoTime() >= deadline) {
                log.debug("Feedback sync round budget spent; the rest waits for the next round")
                return false
            }
            if (!gate.canSendNow(connectionId)) {
                log.debug("Feedback sync can no longer send (service deselected, not granted, or unreachable); ending this round")
                return false
            }
            return true
        }

        fun succeeded() {
            serverErrors = 0
        }

        /** Records [e] for one item through [defer] (count an attempt or not, and when next); false when the round stops. */
        fun failed(what: String, e: Exception, attempts: Int, defer: (countAttempt: Boolean, nextAttemptAt: java.time.OffsetDateTime) -> Unit): Boolean {
            val now = EpistolaClock.offsetDateTime()
            return when (e.supportRequestSyncFailure()) {
                SupportRequestSyncFailure.STOP_ROUND -> {
                    log.warn("Sending deferred; ending this round item=\"{}\" reason=\"{}\"", what, e.message)
                    syncAttempts("deferred").increment()
                    false
                }

                SupportRequestSyncFailure.ITEM_SERVER_ERROR -> {
                    log.warn("The support service failed on an item; trying it again later item=\"{}\" reason=\"{}\"", what, e.message)
                    syncAttempts("deferred").increment()
                    defer(false, now.plus(SupportRequestSyncBackoff.FIRST))
                    ++serverErrors < SupportRequestSyncBackoff.MAX_ITEM_SERVER_ERRORS_IN_A_ROW
                }

                SupportRequestSyncFailure.REFUSED -> {
                    serverErrors = 0
                    log.error("The support service refused an item item=\"{}\" attempt={} reason=\"{}\"", what, attempts + 1, e.message)
                    syncAttempts("failure").increment()
                    defer(true, now.plus(SupportRequestSyncBackoff.afterRefusals(attempts + 1)))
                    true
                }
            }
        }
    }

    /** @return false when the round should stop. */
    private fun retryPendingSupportRequest(round: Round, port: SupportRequestSyncPort): Boolean {
        val pending = ListPendingSyncSupportRequest(limit = 20, connectionId = round.connectionId).query()
        for (supportRequest in pending) {
            if (!round.mayGoOn()) return false
            val proceed = SecurityContext.runWithPrincipal(supportRequestSystemPrincipal(supportRequest.tenantKey)) {
                val supportRequestId = SupportRequestId(supportRequest.id, TenantId(supportRequest.tenantKey))
                if (!stillToSend(OutboundSupportRequestItem.Submission(supportRequestId), round.connectionId)) return@runWithPrincipal true
                try {
                    syncSupportRequest(supportRequest, port, round.connectionId)
                    syncAttempts("success").increment()
                    round.succeeded()
                    true
                } catch (e: Exception) {
                    round.failed("feedback ${supportRequest.id}", e, supportRequest.syncAttempts) { count, next ->
                        // Guarded: a withdrawal while the call was on its way keeps the feedback stopped.
                        RecordSupportRequestSyncFailure(supportRequestId, next, count, round.connectionId).execute()
                    }
                }
            }
            if (!proceed) return false
        }
        return true
    }

    /** @return false when the round should stop. */
    private fun retryPendingAssets(round: Round, port: SupportRequestSyncPort): Boolean {
        val pending = ListUnsyncedSupportRequestAssets(limit = 20, connectionId = round.connectionId).query()
        for (asset in pending) {
            if (!round.mayGoOn()) return false
            val proceed = SecurityContext.runWithPrincipal(supportRequestSystemPrincipal(asset.tenantKey)) {
                val supportRequest = GetSupportRequestDelivered(SupportRequestId(asset.assetId.supportRequestKey, TenantId(asset.tenantKey)), round.connectionId).query()
                    ?: return@runWithPrincipal true
                val content = GetSupportRequestAssetContent(asset.assetId).query() ?: return@runWithPrincipal true
                if (!stillToSend(OutboundSupportRequestItem.Asset(asset.assetId), round.connectionId)) return@runWithPrincipal true
                try {
                    port.uploadAsset(supportRequest, asset.assetId.key, content)
                    MarkSupportRequestAssetSynced(asset.assetId, round.connectionId).execute()
                    round.succeeded()
                    log.info("Sent an asset to Epistola support asset={} feedback={}", asset.id, supportRequest.id)
                    true
                } catch (e: Exception) {
                    round.failed("asset ${asset.id} of feedback ${supportRequest.id}", e, asset.syncAttempts) { count, next ->
                        RecordSupportRequestAssetSyncFailure(asset.assetId, next, count, round.connectionId).execute()
                    }
                }
            }
            if (!proceed) return false
        }
        return true
    }

    // Per-item outcome of the outbound feedback sync. Lets a fleet alert on a rising
    // failure rate (provider down / misconfigured) and confirm the scheduler is doing work.
    private fun syncAttempts(outcome: String): Counter = Counter.builder("epistola.support.requests.sync.attempts")
        .tag("outcome", outcome)
        .register(meterRegistry)

    /** @return false when the round should stop. */
    private fun retryPendingComments(round: Round, port: SupportRequestSyncPort): Boolean {
        val pending = ListUnsyncedComments(limit = 50, connectionId = round.connectionId).query()
        for ((groupKey, comments) in pending.groupBy { it.tenantKey to it.requestId }) {
            val (tenantKey, supportRequestKey) = groupKey
            // GetSupportRequest is tenant-scoped (RequiresPermission); bind a system principal.
            val proceed = SecurityContext.runWithPrincipal(supportRequestSystemPrincipal(tenantKey)) {
                val supportRequestId = SupportRequestId(supportRequestKey, TenantId(tenantKey))
                val supportRequest = GetSupportRequestDelivered(supportRequestId, round.connectionId).query() ?: return@runWithPrincipal true
                if (supportRequest.syncStatus != SyncStatus.SYNCED || supportRequest.externalRef == null) {
                    return@runWithPrincipal true
                }
                for (comment in comments) {
                    if (!round.mayGoOn()) return@runWithPrincipal false
                    val commentId = SupportRequestCommentId(comment.id, supportRequestId)
                    if (!stillToSend(OutboundSupportRequestItem.Comment(commentId), round.connectionId)) continue
                    try {
                        val ref = port.addComment(supportRequest, comment)
                        UpdateSupportRequestCommentExternalRef(id = commentId, externalCommentId = ref.externalCommentId, connectionId = round.connectionId).execute()
                        round.succeeded()
                        log.info("Sent a comment comment={} feedback={}", comment.id, supportRequest.id)
                    } catch (e: Exception) {
                        val goOn = round.failed("comment ${comment.id}", e, comment.syncAttempts) { count, next ->
                            RecordSupportRequestCommentSyncFailure(commentId, next, count, round.connectionId).execute()
                        }
                        if (!goOn) return@runWithPrincipal false
                    }
                }
                true
            }
            if (!proceed) return false
        }
        return true
    }

    /** Statuses changed here after the feedback reached the service. @return false when the round should stop. */
    private fun retryUnsentStatuses(round: Round, port: SupportRequestSyncPort): Boolean {
        for ((supportRequest, attempts) in ListUnsentSupportRequestStatuses(limit = 20, connectionId = round.connectionId).query()) {
            if (!round.mayGoOn()) return false
            val supportRequestId = SupportRequestId(supportRequest.id, TenantId(supportRequest.tenantKey))
            val proceed = SecurityContext.runWithPrincipal(supportRequestSystemPrincipal(supportRequest.tenantKey)) {
                if (!stillToSend(OutboundSupportRequestItem.Status(supportRequestId, supportRequest.status), round.connectionId)) return@runWithPrincipal true
                try {
                    port.updateStatus(supportRequest, supportRequest.status)
                    RecordSupportRequestStatusSync(supportRequestId, sent = supportRequest.status, connectionId = round.connectionId).execute()
                    round.succeeded()
                    log.info("Sent a status status={} feedback={}", supportRequest.status, supportRequest.id)
                    true
                } catch (e: Exception) {
                    round.failed("the status of feedback ${supportRequest.id}", e, attempts) { count, next ->
                        RecordSupportRequestStatusSync(supportRequestId, sent = null, nextAttemptAt = next, countAttempt = count, connectionId = round.connectionId).execute()
                    }
                }
            }
            if (!proceed) return false
        }
        return true
    }

    /**
     * Re-reads [item] right before its call: a withdrawal that landed while the round worked through
     * its batch stops what it has not started. A call already on its way still completes.
     */
    private fun stillToSend(item: OutboundSupportRequestItem, connectionId: java.util.UUID): Boolean = IsSupportRequestItemStillToSend(item, connectionId).query().also {
        if (!it) log.debug("Not sending an item: no longer to be sent (consent withdrawn meanwhile) item={}", item)
    }

    private fun syncSupportRequest(supportRequest: SupportRequest, port: SupportRequestSyncPort, connectionId: java.util.UUID) {
        val supportRequestId = SupportRequestId(supportRequest.id, TenantId(supportRequest.tenantKey))
        val syncResult = port.createTicket(supportRequest)

        // The ticket carries feedback.status as listed; a change made meanwhile is left unsent.
        UpdateSupportRequestSyncRef(
            id = supportRequestId,
            externalRef = syncResult.externalRef,
            externalUrl = syncResult.externalUrl,
            sentStatus = supportRequest.status,
            connectionId = connectionId,
        ).execute()

        log.info("Synced feedback to an external ticket feedback={} ticket={}", supportRequest.id, syncResult.externalRef)
    }

    companion object {
        const val TASK_KEY = "feedback.sync.retry"
        const val ROUTING_KEY = "system:feedback.sync.retry"
        const val TASK_TYPE = "feedback.sync.retry"

        /** The [SupportBackgroundWork] key of a sending round, scheduled or started by a change. */
        const val WORK_KEY = "feedback.sync"
    }
}

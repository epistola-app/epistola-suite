// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.sync

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.metadata.getAs
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.support.connection.SupportBackgroundWork
import app.epistola.suite.support.connection.SupportCluster
import app.epistola.suite.support.requests.commands.SyncSupportRequestComment
import app.epistola.suite.support.requests.commands.SyncSupportRequestPriority
import app.epistola.suite.support.requests.commands.SyncSupportRequestStatus
import app.epistola.suite.support.requests.consent.IsSupportRequestSyncConsentInForce
import app.epistola.suite.support.requests.queries.GetSupportRequestByExternalRef
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component

/**
 * Periodically polls the external sync target for inbound updates (comments, status changes).
 *
 * Sync is installation-wide: one poll returns updates across all tenants, each tagged with
 * its tenant. An update for a tenant whose consent is not in force is skipped (and not applied later). The connection
 * keeps the position, as with collecting generated documents: the poll acknowledges the last update
 * it handled with its next call, and calls until a page comes back empty. On unless `epistola.support.requests.sync.polling.enabled=false`, and only
 * does work when a real sync target is wired and the connection holds the `feedback` scope, so
 * replies from Epistola come back without further configuration.
 *
 * The native scheduled-task lease ensures only one node polls per cycle.
 */
@Component
@ConditionalOnProperty(
    name = ["epistola.support.requests.sync.polling.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class SupportRequestPollScheduler(
    private val supportRequestSyncPort: SupportRequestSyncPort,
    private val gate: SupportRequestSyncGate,
    private val mediator: Mediator,
    private val properties: SupportRequestSyncProperties,
    private val work: SupportBackgroundWork,
) : ClusterScheduledTaskHandler {
    private val log = LoggerFactory.getLogger(javaClass)
    override val taskType: String = TASK_TYPE

    @Bean
    fun supportRequestPollScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.FixedDelay(properties.polling.intervalMs),
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
        // Never owned by a node whose clock is off, which could not send: the other nodes run it.
        requiredCapability = SupportCluster.CAPABILITY,
    )

    private val pacing = SupportRequestPollPacing(properties.polling.intervalMs, properties.polling.maxIntervalMs)

    /**
     * Hands the poll to [SupportBackgroundWork] when it is due ([SupportRequestPollPacing]): the
     * scheduler thread never waits on the support service.
     */
    override fun handle(task: ClusterScheduledTask) {
        if (!pacing.isDue(System.nanoTime())) return
        work.launch(WORK_KEY) { pacing.round(System.nanoTime()) { pollForUpdates() } }
    }

    /** One round; true when it found changes. */
    fun pollForUpdates(): Boolean = MediatorContext.runWithMediator(mediator) { drainUpdates() }

    private fun drainUpdates(): Boolean {
        // Not wired, no `feedback` scope, or the service known unreachable: skip the poll rather
        // than fail the fetch.
        if (!gate.canSendNow()) {
            log.debug("Feedback sync cannot reach the support service now; skipping the poll")
            return false
        }

        // What this run handled and has not acknowledged yet. The connection keeps the position:
        // each call acknowledges what the previous page brought, and the run calls until a page comes
        // back empty, so what it handled is acknowledged in the same run.
        var acknowledge: Long? = null
        var found = false
        var pages = 0
        // At most `round-budget-ms` per run: a slow service leaves the rest of the pages for the next.
        val deadline = System.nanoTime() + properties.roundBudgetMs * 1_000_000
        while (pages++ < MAX_PAGES_PER_RUN && System.nanoTime() < deadline) {
            val page = try {
                supportRequestSyncPort.fetchUpdates(acknowledge).also { acknowledge = null }
            } catch (e: Exception) {
                if (e.isSupportRequestSyncDeferred()) {
                    // Unreachable: what was handled is acknowledged by the next poll, not retried now.
                    log.debug("Epistola support service unavailable while polling support request changes: {}", e.message)
                    return found
                }
                throw e
            }
            if (page.lastSequence == null) break
            found = true
            log.info("Fetched {} support request changes up to {}", page.updates.size, page.lastSequence)

            var lastProcessed: Long? = null
            for (update in page.updates) {
                try {
                    processUpdate(update.tenantKey, update)
                    lastProcessed = update.seq
                } catch (e: Exception) {
                    log.error(
                        "Failed to process update seq {} for tenant {} (ref={}): {}",
                        update.seq,
                        update.tenantKey,
                        update.externalRef,
                        e.message,
                    )
                    // Acknowledge only what was handled before it, so it comes again next cycle.
                    lastProcessed?.let { acknowledgeOnly(it) }
                    return true
                }
            }
            // A page handled to its end is acknowledged up to its last change, also one of a type this
            // suite skips, so those cannot stall the feed.
            acknowledge = page.lastSequence
            // Nothing more follows: the acknowledgement below is the run's last call.
            if (!page.hasMore) break
        }
        // Handled but not yet acknowledged (the feed ran out, or the run its budget or page limit):
        // acknowledged now, so a replay never applies an older change over a newer one made here.
        acknowledge?.let { acknowledgeOnly(it) }
        return found
    }

    /** Acknowledges [sequence] and leaves what the call brings for the next cycle. */
    private fun acknowledgeOnly(sequence: Long) {
        try {
            supportRequestSyncPort.fetchUpdates(sequence)
        } catch (e: Exception) {
            if (!e.isSupportRequestSyncDeferred()) throw e
            log.debug("Epistola support service unavailable while acknowledging support request changes: {}", e.message)
        }
    }

    private fun processUpdate(tenantKey: TenantKey, update: ExternalUpdate) {
        // The poll runs on a scheduler thread with no HTTP principal; the inbound commands
        // are tenant-scoped (RequiresPermission), so bind a system principal for the tenant.
        SecurityContext.runWithPrincipal(supportRequestSystemPrincipal(tenantKey)) {
            applyUpdate(tenantKey, update)
        }
    }

    private fun applyUpdate(tenantKey: TenantKey, update: ExternalUpdate) {
        // A tenant that withdrew its consent no longer takes replies from Epistola either.
        if (!IsSupportRequestSyncConsentInForce(tenantKey).query()) {
            log.debug("Skipping feedback update seq {}: tenant {} has no consent in force", update.seq, tenantKey)
            return
        }
        val supportRequestId = GetSupportRequestByExternalRef(tenantKey, update.externalRef).query() ?: run {
            log.debug("No feedback found for external ref {} in tenant {}", update.externalRef, tenantKey)
            return
        }

        when (update) {
            is ExternalUpdate.Comment -> {
                SyncSupportRequestComment(
                    tenantKey = tenantKey,
                    supportRequestId = supportRequestId.key,
                    body = update.body,
                    authorName = update.authorName,
                    authorEmail = update.authorEmail,
                    externalCommentId = update.externalCommentId,
                ).execute()
                log.debug("Synced comment {} for feedback {}", update.externalCommentId, supportRequestId.key)
            }

            is ExternalUpdate.StatusChange -> {
                // SyncSupportRequestStatus (not UpdateSupportRequestStatus) so this does not bounce back out.
                SyncSupportRequestStatus(id = supportRequestId, status = update.newStatus).execute()
                log.debug("Updated feedback {} status to {}", supportRequestId.key, update.newStatus)
            }

            is ExternalUpdate.PriorityChange -> {
                SyncSupportRequestPriority(id = supportRequestId, priority = update.newPriority).execute()
                log.debug("Updated support request {} priority to {}", supportRequestId.key, update.newPriority)
            }
        }
    }

    companion object {
        /** Bound on pages drained per cycle so one run can't loop unbounded on a large backlog. */
        internal const val MAX_PAGES_PER_RUN = 50

        /** The [SupportBackgroundWork] key of a poll. */
        const val WORK_KEY = "feedback.poll"

        const val TASK_KEY = "feedback.sync.poll"
        const val ROUTING_KEY = "system:feedback.sync.poll"
        const val TASK_TYPE = "feedback.sync.poll"
    }
}

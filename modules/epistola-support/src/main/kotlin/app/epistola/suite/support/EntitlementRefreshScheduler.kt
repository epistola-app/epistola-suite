package app.epistola.suite.support

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Backstop refresh of the hub entitlement set. Changes normally propagate near-instantly via the
 * revision header the hub stamps on every response (see [EntitlementRevisionTrigger]), and the
 * initial fetch happens right after registration; this poll is the safety net for an installation
 * with no other hub traffic and for recovering after an outage — hence a long default (6h).
 * Last-known-good is preserved on failure. Every node refreshes its own shared `app_metadata` row
 * idempotently, so no [app.epistola.suite.scheduling.SchedulerLock] is needed. Active only when the
 * support tier is enabled.
 */
@Component
@EnableScheduling
@ConditionalOnSupportModule
@ConditionalOnProperty(prefix = "epistola.support", name = ["enabled"], havingValue = "true")
class EntitlementRefreshScheduler(
    private val entitlementSync: EntitlementSyncService,
) {
    @Scheduled(fixedDelayString = "\${epistola.support.entitlements.refresh-ms:21600000}")
    fun refresh() {
        entitlementSync.refresh()
    }
}

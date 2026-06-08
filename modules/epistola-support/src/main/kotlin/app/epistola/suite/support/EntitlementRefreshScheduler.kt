package app.epistola.suite.support

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Periodically refreshes the hub entitlement set so changes (new grants, revocations, expiries)
 * propagate without a restart. The initial fetch happens right after registration (see
 * [SupportConfiguration]); this keeps it current. Last-known-good is preserved on failure. Every
 * node refreshes its own shared `app_metadata` row idempotently, so no [app.epistola.suite.scheduling.SchedulerLock]
 * is needed. Active only when the support tier is enabled.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "epistola.support", name = ["enabled"], havingValue = "true")
class EntitlementRefreshScheduler(
    private val entitlementSync: EntitlementSyncService,
) {
    @Scheduled(fixedDelayString = "\${epistola.support.entitlements.refresh-ms:3600000}")
    fun refresh() {
        entitlementSync.refresh()
    }
}

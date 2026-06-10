package app.epistola.suite.support

import app.epistola.suite.background.BackgroundExecutionContext
import app.epistola.suite.mediator.execute
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

/**
 * Thin orchestrator that dispatches the [RefreshEntitlements] command from the non-request contexts
 * that need it (startup hook, refresh scheduler, revision trigger) and the UI handler. It binds the
 * mediator scope (background callers have no HTTP-bound mediator) and stays **fail-open**: a refresh
 * must never crash its caller or downgrade the stored set. The actual work — and its metrics/audit —
 * lives in [RefreshEntitlementsHandler]. This mirrors how `TenantSnapshotSyncService` wraps the
 * `BuildTenantSnapshot` command.
 */
@Service
@ConditionalOnProperty(prefix = "epistola.support", name = ["enabled"], havingValue = "true")
class EntitlementSyncService(
    private val backgroundExecutionContext: BackgroundExecutionContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun refresh() {
        runCatching {
            backgroundExecutionContext.run { RefreshEntitlements().execute() }
        }.onFailure { e ->
            log.warn("Entitlement refresh failed; keeping last-known-good entitlements: {}", e.message)
        }
    }
}

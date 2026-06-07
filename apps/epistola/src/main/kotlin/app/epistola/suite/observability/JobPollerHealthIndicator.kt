package app.epistola.suite.observability

import app.epistola.suite.documents.JobPollingProperties
import app.epistola.suite.documents.batch.JobPoller
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component

/**
 * Reports whether the background job poller is alive, from the age of its last
 * drain cycle. The poller's scheduled fallback fires every `interval-ms` even
 * when the queue is empty, so a stale heartbeat means the drain thread is
 * wedged.
 *
 * [JobPoller] is injected optionally: when polling is disabled
 * (`epistola.generation.polling.enabled=false`) no poller exists and the
 * indicator reports UP with a `disabled` note.
 *
 * Contributes to the **overall** health endpoint, not the readiness group:
 * pending jobs fail over to other instances via `SELECT ... FOR UPDATE SKIP
 * LOCKED`, so a wedged poller is an alert condition — not a reason to drop the
 * pod's UI/API traffic from the Service.
 */
@Component
class JobPollerHealthIndicator(
    private val jobPoller: JobPoller? = null,
    pollingProperties: JobPollingProperties? = null,
) : HealthIndicator {

    private val staleThresholdMs = (pollingProperties?.intervalMs ?: DEFAULT_INTERVAL_MS) * STALE_MULTIPLIER

    override fun health(): Health {
        val poller = jobPoller ?: return Health.up().withDetail("polling", "disabled").build()
        return evaluate(poller.lastPollAgeMillis())
    }

    /** Split out from bean wiring so the staleness threshold is unit-testable. */
    internal fun evaluate(ageMillis: Long): Health {
        val builder = if (ageMillis <= staleThresholdMs) Health.up() else Health.down()
        return builder
            .withDetail("lastPollAgeMs", ageMillis)
            .withDetail("staleThresholdMs", staleThresholdMs)
            .build()
    }

    private companion object {
        const val DEFAULT_INTERVAL_MS = 5_000L

        /** Tolerate a few missed cycles before declaring the poller wedged. */
        const val STALE_MULTIPLIER = 4L
    }
}

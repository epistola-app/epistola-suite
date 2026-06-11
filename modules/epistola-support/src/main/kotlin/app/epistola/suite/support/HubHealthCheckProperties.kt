package app.epistola.suite.support

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Tuning for the per-node hub health-check keepalive. Connectivity is normally recorded as a
 * side-effect of real hub operations; the keepalive only probes when none has happened within
 * [staleAfter], so a busy node never probes and a quiet one stays fresh.
 */
@ConfigurationProperties(prefix = "epistola.support.health-check")
data class HubHealthCheckProperties(
    /** Cadence for checking whether this node's hub connectivity has gone stale. */
    val intervalMs: Long = 60_000,

    /** Probe only when the last hub contact (any operation) is older than this. */
    val staleAfter: Duration = Duration.ofMinutes(5),
)

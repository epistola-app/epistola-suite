package app.epistola.suite.support

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Tuning for the per-node hub health-check keepalive. Connectivity is normally recorded as a
 * side-effect of real hub operations; the keepalive only probes when none has happened within
 * [staleAfter], so a busy node never probes and a quiet one stays fresh. The scheduler cadence is
 * `epistola.support.health-check.interval-ms` (read directly by `@Scheduled`).
 */
@ConfigurationProperties(prefix = "epistola.support.health-check")
data class HubHealthCheckProperties(
    /** Probe only when the last hub contact (any operation) is older than this. */
    val staleAfter: Duration = Duration.ofMinutes(5),
)

package app.epistola.suite.support

import app.epistola.suite.time.EpistolaClock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Per-node keepalive that keeps this node's hub connectivity fresh during quiet periods. Real hub
 * operations already record connectivity; this only probes when none has happened within
 * [HubHealthCheckProperties.staleAfter]. **No `SchedulerLock`** — every node checks itself, so a
 * partial outage (one node can't reach the hub) is recorded per node. Active only when the support
 * tier is enabled.
 */
@Component
@EnableScheduling
@EnableConfigurationProperties(HubHealthCheckProperties::class)
@ConditionalOnProperty(
    prefix = "epistola.support",
    name = ["enabled"],
    havingValue = "true",
)
class HubHealthCheckScheduler(
    private val connectivity: HubConnectivityService,
    private val properties: HubHealthCheckProperties,
) {
    @Scheduled(fixedDelayString = "\${epistola.support.health-check.interval-ms:60000}")
    fun refreshIfStale() {
        val lastContact = connectivity.currentNode().lastCheckedAt
        val stale = lastContact == null || Duration.between(lastContact, EpistolaClock.instant()) >= properties.staleAfter
        if (stale) connectivity.refresh()
    }
}

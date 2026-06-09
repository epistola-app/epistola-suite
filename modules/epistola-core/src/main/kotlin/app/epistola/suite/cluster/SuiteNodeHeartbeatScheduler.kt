package app.epistola.suite.cluster

import app.epistola.suite.observability.recordScheduledTask
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@EnableScheduling
@ConditionalOnProperty(
    name = ["epistola.cluster.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class SuiteNodeHeartbeatScheduler(
    private val registry: SuiteNodeRegistry,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${epistola.cluster.heartbeat-interval-ms:10000}")
    fun heartbeat() = meterRegistry.recordScheduledTask("suite-node-heartbeat") {
        val node = registry.heartbeat()
        log.debug("Suite node heartbeat complete: nodeId={}", node.nodeId)
    }
}

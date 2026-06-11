package app.epistola.suite.cluster

import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/**
 * Single shared daemon thread for this node's lightweight periodic cluster
 * maintenance: the liveness heartbeat and in-flight lease renewal.
 *
 * These tasks are all small, bounded database operations and — crucially — none
 * of them run handler work (handlers execute on the poller's `@Scheduled`
 * thread). So they share one thread without reintroducing the starvation the
 * heartbeat decoupling guards against: a long handler still cannot block this
 * thread. Consolidating here keeps heartbeat and lease renewal off the shared
 * Spring scheduling pool without spending a thread per concern.
 *
 * Each scheduled task is registered independently (its own
 * `scheduleWithFixedDelay`) and catches its own exceptions, so one cannot
 * cancel or starve another.
 */
@Component
class ClusterMaintenanceExecutor {
    val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "cluster-maintenance").apply { isDaemon = true }
    }

    @PreDestroy
    fun shutdown() {
        scheduler.shutdownNow()
    }
}

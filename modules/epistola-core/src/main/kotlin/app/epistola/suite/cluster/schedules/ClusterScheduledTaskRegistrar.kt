package app.epistola.suite.cluster.schedules

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Startup reconciler for code-defined scheduled task definitions.
 *
 * Every application node invokes this after Spring is ready. Registration is an
 * idempotent upsert, so multiple nodes can safely register the same
 * definitions against the shared database. The registrar does not currently
 * delete or tombstone rows for definitions that disappeared from code; that
 * lifecycle policy is intentionally left explicit for a later iteration.
 */
@Component
class ClusterScheduledTaskRegistrar(
    private val registry: ClusterScheduledTaskRegistry,
    private val definitions: List<ClusterScheduledTaskDefinition>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun registerDefinitions() {
        definitions.forEach { definition ->
            val task = registry.upsert(definition)
            log.debug("Registered cluster scheduled task '{}' nextDueAt={}", task.taskKey, task.nextDueAt)
        }
    }
}

package app.epistola.suite.cluster

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

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

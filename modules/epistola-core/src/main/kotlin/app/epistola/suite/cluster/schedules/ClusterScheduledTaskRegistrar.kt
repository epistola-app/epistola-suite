package app.epistola.suite.cluster.schedules

import app.epistola.suite.observability.NodeIdentity
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.info.BuildProperties
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Startup reconciler for code-defined scheduled task definitions.
 *
 * Every application node invokes this after Spring is ready. Registration is an
 * idempotent upsert, so multiple nodes can safely register the same definitions
 * against the shared database.
 *
 * After upserting, the node records itself in
 * `cluster_scheduled_task_registrations` as vouching for exactly the definitions
 * present in its code, and prunes any registration rows for definitions it no
 * longer carries. That per-node "who vouches for this schedule" set is what
 * [ClusterScheduledTaskReconciler] uses to retire definitions that disappeared
 * from code once no active node vouches for them. Re-registering a definition
 * also clears any prior retirement, so a returning definition is reclaimed.
 */
@Component
class ClusterScheduledTaskRegistrar(
    private val registry: ClusterScheduledTaskRegistry,
    private val definitions: List<ClusterScheduledTaskDefinition>,
    private val nodeIdentity: NodeIdentity,
    private val buildProperties: BuildProperties? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun registerDefinitions() {
        // Upsert every definition and record this node's vouch in one transaction,
        // so a reclaimed (un-retired) task is vouched for in the same commit that
        // clears its retirement — a concurrent reconcile can never observe it
        // un-retired-but-unvouched and wrongly re-retire it.
        registry.registerAll(definitions, buildProperties?.version)
        log.debug(
            "Registered {} cluster scheduled task definition(s) for node {}",
            definitions.size,
            nodeIdentity.nodeId,
        )
    }
}

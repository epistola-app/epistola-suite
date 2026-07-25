// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.cluster.schedules

import app.epistola.suite.observability.NodeIdentity
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Startup registrar for code-defined scheduled task definitions.
 *
 * Every application node invokes this after Spring is ready. In one transaction
 * ([ClusterScheduledTaskRegistry.registerAll]) it upserts every definition and
 * records this node in `cluster_scheduled_task_registrations` as carrying exactly
 * the definitions present in its code, pruning rows for definitions it no longer
 * carries. That per-node "who carries this schedule" set is what
 * [ClusterScheduledTaskReconciler] uses to delete definitions that disappeared
 * from code once no live node carries them. Doing it atomically means a
 * re-created task is vouched for in the same commit, so a concurrent reconcile
 * can never observe it created-but-unvouched and wrongly delete it.
 */
@Component
class ClusterScheduledTaskRegistrar(
    private val registry: ClusterScheduledTaskRegistry,
    private val definitions: List<ClusterScheduledTaskDefinition>,
    private val nodeIdentity: NodeIdentity,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun registerDefinitions() {
        registry.registerAll(definitions)
        log.debug(
            "Registered {} cluster scheduled task definition(s) for node {}",
            definitions.size,
            nodeIdentity.nodeId,
        )
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.cluster.timers

import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.SystemInternal
import org.springframework.stereotype.Component

/**
 * Reads a single one-shot cluster timer by its stable idempotency key.
 *
 * This is the mediator-facing read API for application and operations code.
 * Scheduler internals still use [ClusterTimerRegistry] directly for lease
 * transitions.
 */
data class GetClusterTimer(
    val timerKey: String,
) : Query<ClusterTimer?>,
    SystemInternal

/**
 * Lists one-shot cluster timers for operational inspection.
 *
 * Results are ordered by due time and key, matching the registry's operational
 * listing semantics. This is not a claim query and does not imply ownership.
 */
data class ListClusterTimers(
    val limit: Int = 100,
) : Query<List<ClusterTimer>>,
    SystemInternal

/**
 * Mediator query handler backed by the internal timer persistence boundary.
 */
@Component
class GetClusterTimerHandler(
    private val registry: ClusterTimerRegistry,
) : QueryHandler<GetClusterTimer, ClusterTimer?> {
    override fun handle(query: GetClusterTimer): ClusterTimer? = registry.find(query.timerKey)
}

/**
 * Mediator query handler for the Operations cluster timer list.
 */
@Component
class ListClusterTimersHandler(
    private val registry: ClusterTimerRegistry,
) : QueryHandler<ListClusterTimers, List<ClusterTimer>> {
    override fun handle(query: ListClusterTimers): List<ClusterTimer> = registry.list(query.limit)
}

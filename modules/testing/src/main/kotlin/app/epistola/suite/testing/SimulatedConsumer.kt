// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.generation.collect.commands.AcknowledgeGenerationResults
import app.epistola.suite.generation.collect.commands.TouchConsumerNode
import app.epistola.suite.generation.collect.domain.Partition
import app.epistola.suite.generation.collect.domain.PartitionAssignment
import app.epistola.suite.generation.collect.queries.FetchGenerationResults
import app.epistola.suite.generation.collect.queries.FetchResultsPage
import app.epistola.suite.mediator.Mediator
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Test-side stand-in for one (consumerId, nodeId) pair on the collect protocol.
 *
 * Wraps the same commands and queries that
 * `EpistolaDocumentGenerationApi.collectGenerationResults` would dispatch in
 * production, just driven via the mediator instead of through HTTP. We're
 * testing the machinery (ring, cursors, partition routing); the controller is
 * trivial pass-through and is covered separately by Step F's smoke.
 *
 * Construct via [SimulatedConsumerFactory.consumer] inside a scenario test —
 * the factory wires in the suite-side dependencies (mediator + jdbi).
 *
 * Stateful only in the sense that it caches the most recent partition
 * assignment so [routingKeyToMe] can answer between polls without a fresh
 * Touch. State that lives in the DB (cursor, last-seen) is the source of
 * truth.
 */
class SimulatedConsumer internal constructor(
    val tenantKey: TenantKey,
    val consumerId: String,
    val nodeId: String,
    private val mediator: Mediator,
    private val jdbi: Jdbi,
) {
    @Volatile
    private var lastAssignment: PartitionAssignment? = null

    /**
     * Touch + (optional) ack + fetch — the same three-step orchestration the
     * real `/generation/collect` endpoint does. Returns the page of rows the
     * consumer would have received over the wire.
     *
     * @param acknowledgeUpTo optional sequence to ack before fetching, mirroring
     *   the wire `acknowledgeUpTo` request body field. Pass `null` on the first
     *   poll, then the highest sequence from the previous page on subsequent.
     * @param limit page size; defaults to 100, the same default the production
     *   endpoint uses.
     */
    fun poll(acknowledgeUpTo: Long? = null, limit: Int = 100): FetchResultsPage {
        val assignment = mediator.send(TouchConsumerNode(tenantKey, consumerId, nodeId))
        lastAssignment = assignment

        if (acknowledgeUpTo != null && acknowledgeUpTo > 0L && assignment.mine.isNotEmpty()) {
            mediator.send(
                AcknowledgeGenerationResults(
                    tenantId = tenantKey,
                    consumerId = consumerId,
                    partitions = assignment.mine.toSet(),
                    acknowledgeUpTo = acknowledgeUpTo,
                ),
            )
        }

        if (assignment.mine.isEmpty()) {
            return FetchResultsPage(emptyList(), hasMore = false, lastSequence = null)
        }
        return mediator.query(
            FetchGenerationResults(
                tenantId = tenantKey,
                consumerId = consumerId,
                partitions = assignment.mine.toSet(),
                limit = limit,
            ),
        )
    }

    /**
     * Send an ack without polling for new results. Useful when a test needs to
     * advance the cursor explicitly between scenario steps.
     */
    fun acknowledge(uptoSequence: Long) {
        val partitions = (lastAssignment?.mine ?: emptyList()).toSet()
        if (partitions.isEmpty()) return
        mediator.send(AcknowledgeGenerationResults(tenantKey, consumerId, partitions, uptoSequence))
    }

    /**
     * Hard-disconnect this node by deleting its row from
     * `consumer_node_assignments`. The next time anyone touches, the ring
     * recomputes without us. Faster than waiting for `idle-timeout-ms`.
     */
    fun goSilent() {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                DELETE FROM consumer_node_assignments
                WHERE tenant_key = :tenantKey AND consumer_id = :consumerId AND node_id = :nodeId
                """,
            )
                .bind("tenantKey", tenantKey)
                .bind("consumerId", consumerId)
                .bind("nodeId", nodeId)
                .execute()
        }
        lastAssignment = null
    }

    /**
     * The consumer's currently-known partition assignment, or null if it
     * hasn't polled yet. Set as a side effect of [poll].
     */
    fun assignment(): PartitionAssignment? = lastAssignment

    /**
     * Mirror of the contract client's `routingKeyToMe(...)` helper — returns a
     * routing key guaranteed to land on one of THIS consumer-node's currently
     * assigned partitions. Returns `null` if no partitions are assigned (the
     * consumer hasn't polled yet, or has zero partitions).
     *
     * Algorithm matches `client-kotlin-spring-restclient`:
     * - if [baseKey] already hashes to one of mine, return it as-is
     * - else find a partition prefix `<n>:` whose total hash lands on mine
     * - fall back to `<firstMine>:<baseKey>` if no prefix in `mine` works
     */
    fun routingKeyToMe(baseKey: String = UUID.randomUUID().toString()): String? {
        val mine = lastAssignment?.mine?.toSet() ?: return null
        if (mine.isEmpty()) return null
        if (Partition.partitionFor(baseKey) in mine) return baseKey
        for (p in mine) {
            val candidate = "$p:$baseKey"
            if (Partition.partitionFor(candidate) in mine) return candidate
        }
        return "${mine.first()}:$baseKey"
    }
}

/**
 * Spring-managed factory so scenario tests can construct [SimulatedConsumer]s
 * with one line: `consumers.consumer(tenant, "alice", "node-1")`.
 */
@Component
class SimulatedConsumerFactory(
    private val mediator: Mediator,
    private val jdbi: Jdbi,
) {
    fun consumer(tenantKey: TenantKey, consumerId: String, nodeId: String): SimulatedConsumer = SimulatedConsumer(
        tenantKey = tenantKey,
        consumerId = consumerId,
        nodeId = nodeId,
        mediator = mediator,
        jdbi = jdbi,
    )
}

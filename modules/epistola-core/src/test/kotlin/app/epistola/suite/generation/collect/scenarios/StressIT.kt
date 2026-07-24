// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.collect.scenarios

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Higher-volume scenario: N submits across M consumers, all results must be
 * delivered at least once in aggregate (the cluster of consumers covers every
 * job). Tagged "stress" so it can be skipped from the fast feedback loop and
 * run on the load-test profile.
 *
 * Not a perf measurement — that's Step E. This one just proves the protocol
 * stays correct under volume.
 */
@Tag("stress")
class StressIT : CollectScenarioTestBase() {

    @Test
    fun `100 jobs across 4 consumer nodes — every job is delivered at least once`() {
        scenario {
            given { provisionScenario() }.whenever { setup ->
                val nodes = (1..4).map { consumers.consumer(setup.tenant.id, "consumer-1", "node-$it") }
                // First poll registers all four nodes on the ring.
                nodes.forEach { it.poll() }

                // Submit 100 jobs with routing keys that distribute across all
                // nodes (using each node's routingKeyToMe in round-robin).
                val requests = (1..100).map { i ->
                    val targetNode = nodes[i % nodes.size]
                    val rk = targetNode.routingKeyToMe("job-$i") ?: error("no assignment for ${targetNode.nodeId}")
                    submit(setup, routingKey = rk)
                }
                // Complete all of them.
                requests.forEach { controllable.complete(it) }

                // Every node drains its assigned partitions until hasMore is false,
                // tracking what it saw. Together they should cover every job.
                val seenByNode = nodes.associate { node ->
                    val seen = mutableSetOf<app.epistola.suite.common.ids.GenerationRequestKey>()
                    var page = node.poll(limit = 200)
                    seen.addAll(page.rows.map { it.requestId })
                    while (page.hasMore) {
                        page = node.poll(acknowledgeUpTo = page.lastSequence, limit = 200)
                        seen.addAll(page.rows.map { it.requestId })
                    }
                    node.nodeId to seen
                }

                // Every submitted request must have been delivered to at least one node.
                val allSeen = seenByNode.values.flatten().toSet()
                assertThat(allSeen)
                    .`as`("the cluster collectively delivered %d/%d jobs", allSeen.size, requests.size)
                    .containsAll(requests.map { it.id })

                // Load was actually distributed — no single node monopolized.
                val perNodeCount = seenByNode.values.map { it.size }
                assertThat(perNodeCount.max()).isLessThan(requests.size)
                assertThat(perNodeCount.min()).isGreaterThan(0)
            }
        }
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.collect.scenarios

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pin the v0.3 affinity contract: a job submitted with `routingKeyToMe(...)`
 * lands on the partition assigned to the SUBMITTING node, so that node
 * receives its own results back.
 *
 * This is the core promise the consistent hash ring is paying its complexity
 * for. Without affinity, a submitting Valtimo instance has no guarantee its
 * generation result comes back to itself, and the whole "BPMN process advances
 * locally on the same node" story breaks down.
 */
class SubmittingNodeAffinityIT : CollectScenarioTestBase() {

    @Test
    fun `result lands on the submitting node when routingKeyToMe is used`() {
        scenario {
            given { provisionScenario() }.whenever { setup ->
                // Two nodes for the same consumer. Both poll, then alice polls AGAIN
                // so her cached assignment reflects the post-bob ring (otherwise
                // it's stale from when alice was the only node and owned ALL
                // partitions, which would let `routingKeyToMe` pick a partition
                // bob now owns).
                val alice = consumers.consumer(setup.tenant.id, "consumer-1", "alice")
                val bob = consumers.consumer(setup.tenant.id, "consumer-1", "bob")
                alice.poll()
                bob.poll()
                alice.poll()

                // Alice picks a routing key that lands on one of HER partitions and
                // submits the job tagged with it.
                val routingKey = alice.routingKeyToMe("order-42")
                    ?: error("alice has no partition assignment yet")
                val request = submit(setup, routingKey = routingKey)
                controllable.complete(request)

                // Alice gets the result; bob doesn't.
                val alicePage = alice.poll()
                val bobPage = bob.poll()

                assertThat(alicePage.rows.map { it.requestId }).contains(request.id)
                assertThat(bobPage.rows.map { it.requestId }).doesNotContain(request.id)
            }
        }
    }

    @Test
    fun `submit without routingKey may or may not land on the submitter — no affinity guarantee`() {
        scenario {
            given { provisionScenario() }.whenever { setup ->
                // Without routingKeyToMe, the emitter falls back to using the
                // requestId as the routing key. That hashes uniformly across
                // partitions. With many submits, both nodes should see roughly
                // half the results — pin that the routing IS happening (no node
                // gets everything, no node gets nothing).
                val alice = consumers.consumer(setup.tenant.id, "consumer-1", "alice")
                val bob = consumers.consumer(setup.tenant.id, "consumer-1", "bob")
                alice.poll()
                bob.poll()

                val requests = (1..40).map { submit(setup) }
                requests.forEach { controllable.complete(it) }

                val alicePage = alice.poll()
                val bobPage = bob.poll()
                val ids = (alicePage.rows + bobPage.rows).map { it.requestId }.toSet()

                // Every request was delivered (between the two nodes).
                assertThat(ids).containsAll(requests.map { it.id })
                // And the work was actually split — neither node monopolized.
                assertThat(alicePage.rows).isNotEmpty
                assertThat(bobPage.rows).isNotEmpty
            }
        }
    }
}

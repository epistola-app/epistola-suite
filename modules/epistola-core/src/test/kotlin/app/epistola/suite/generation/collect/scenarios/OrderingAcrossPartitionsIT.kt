// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.collect.scenarios

import app.epistola.suite.generation.collect.domain.Partition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `FetchGenerationResults` returns rows ordered by global `sequence` ASC,
 * regardless of which partition they came from. The cursor mechanism then
 * advances per partition, but the wire-order the consumer sees is the global
 * monotonic sequence — important so consumers that process in-order see a
 * consistent stream rather than per-partition jumps.
 */
class OrderingAcrossPartitionsIT : CollectScenarioTestBase() {

    @Test
    fun `results across many partitions are returned in global sequence order`() {
        scenario {
            given { provisionScenario() }.whenever { setup ->
                // Pick routing keys that demonstrably hash to several different
                // partitions, so we know the test is actually exercising the
                // cross-partition ordering.
                val routingKeys = listOf("alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta")
                val partitionsHit = routingKeys.map { Partition.partitionFor(it) }.toSet()
                assertThat(partitionsHit.size)
                    .`as`("test expects routing keys to hit multiple partitions; got %s", partitionsHit)
                    .isGreaterThan(2)

                // Submit + complete in the same order. The emitter assigns
                // monotonic `sequence` values via BIGSERIAL in submit order.
                val requests = routingKeys.map { rk -> submit(setup, routingKey = rk) }
                requests.forEach { controllable.complete(it) }

                val alice = consumers.consumer(setup.tenant.id, "consumer-1", "alice")
                val page = alice.poll()

                // Whichever subset of partitions belongs to alice, the rows from
                // those partitions should still appear in ascending sequence.
                val sequences = page.rows.map { it.sequence }
                assertThat(sequences).isSortedAccordingTo(Comparator.naturalOrder())
            }
        }
    }

    @Test
    fun `out-of-order completion still produces in-order delivery by sequence`() {
        scenario {
            given { provisionScenario() }.whenever { setup ->
                // Submit four requests; complete them in reverse order. The emit
                // sequence is determined by the COMPLETION timestamp (BIGSERIAL on
                // generation_results), not by submit time. So the consumer's view
                // is in completion order.
                val r1 = submit(setup, routingKey = "k-1")
                val r2 = submit(setup, routingKey = "k-2")
                val r3 = submit(setup, routingKey = "k-3")
                val r4 = submit(setup, routingKey = "k-4")
                // Complete in reverse.
                controllable.complete(r4)
                controllable.complete(r3)
                controllable.complete(r2)
                controllable.complete(r1)

                val alice = consumers.consumer(setup.tenant.id, "consumer-1", "alice")
                val page = alice.poll()
                val orderedReqs = page.rows.sortedBy { it.sequence }.map { it.requestId }

                // Whichever of these the consumer owns, they appear in completion
                // order. Find the indices of the requests that are present and
                // verify their relative order matches r4, r3, r2, r1.
                val expectedOrder = listOf(r4.id, r3.id, r2.id, r1.id)
                val present = expectedOrder.filter { it in orderedReqs }
                assertThat(orderedReqs.filter { it in present })
                    .containsExactlyElementsOf(present)
            }
        }
    }
}

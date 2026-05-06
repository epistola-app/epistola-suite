package app.epistola.suite.generation.collect.scenarios

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The headline failover scenario: a node disappears mid-flight; its assigned
 * partitions reassign to surviving nodes; results that were destined for the
 * dead node are picked up by the new owner instead.
 *
 * Pinned because this is the property the consistent hash ring exists to
 * guarantee. Without it the BPMN executions waiting on the dead node's
 * results would hang forever.
 */
class RebalanceRedeliveryIT : CollectScenarioTestBase() {

    @Test
    fun `result destined for a silent node is delivered to the surviving node on rebalance`() {
        scenario {
            given { provisionScenario() }.whenever { setup ->
                val alice = consumers.consumer(setup.tenant.id, "consumer-1", "alice")
                val bob = consumers.consumer(setup.tenant.id, "consumer-1", "bob")
                // Touch sequence: alice (alone, gets ALL 64), bob (joins, gets ~32),
                // alice again so her cached assignment reflects the post-bob split.
                alice.poll()
                bob.poll()
                alice.poll()
                val aliceAssignment = alice.assignment()!!.mine.toSet()
                val bobAssignment = bob.assignment()!!.mine.toSet()
                assertThat(aliceAssignment.intersect(bobAssignment)).isEmpty()

                // Alice submits a job for one of HER partitions.
                val routingKey = alice.routingKeyToMe("job-pre-failover") ?: error("alice has no partitions")
                val request = submit(setup, routingKey = routingKey)

                // Alice goes silent BEFORE the result is emitted (simulating crash).
                alice.goSilent()

                // Now we complete the job. The emit lands on the partition computed
                // from `routingKey` — that partition USED to be alice's. After
                // alice's heartbeat row is gone, bob's next poll should reassign
                // the partition to him and he should receive the result.
                controllable.complete(request)

                val bobAfter = bob.poll()
                assertThat(bobAfter.rows.map { it.requestId })
                    .`as`("bob (the surviving node) should receive alice's orphaned result")
                    .contains(request.id)
            }
        }
    }

    @Test
    fun `unacked rows owned by a dead node are redelivered to surviving nodes`() {
        scenario {
            given { provisionScenario() }.whenever { setup ->
                val alice = consumers.consumer(setup.tenant.id, "consumer-1", "alice")
                val bob = consumers.consumer(setup.tenant.id, "consumer-1", "bob")
                alice.poll()
                bob.poll()
                alice.poll() // refresh post-bob; otherwise alice's cached assignment is stale ALL-64

                // Submit a job pinned to alice; complete it; alice polls but DOESN'T ack.
                val routingKey = alice.routingKeyToMe("unacked-by-alice") ?: error("alice has no partitions")
                val request = submit(setup, routingKey = routingKey)
                controllable.complete(request)
                val alicePage = alice.poll()
                assertThat(alicePage.rows.map { it.requestId }).contains(request.id)
                // ... no ack ...

                // Alice dies. Bob's next poll picks up the partition + the unacked row.
                alice.goSilent()
                val bobPage = bob.poll()
                assertThat(bobPage.rows.map { it.requestId })
                    .`as`("the unacked result should be redelivered to bob")
                    .contains(request.id)
            }
        }
    }
}

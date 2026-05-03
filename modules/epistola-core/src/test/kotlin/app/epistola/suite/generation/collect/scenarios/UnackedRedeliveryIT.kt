package app.epistola.suite.generation.collect.scenarios

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The cursor in `consumer_partition_cursors` is the source of truth — a
 * consumer that polls without acking will receive the same rows again on its
 * next poll. Pinned because this is what makes the protocol crash-safe: a
 * consumer that processes results and dies before acking will redeliver after
 * restart, preserving at-least-once.
 */
class UnackedRedeliveryIT : CollectScenarioTestBase() {

    @Test
    fun `polling without acking returns the same rows on the next poll`() {
        scenario {
            given { provisionScenario() }.whenever { setup ->
                val alice = consumers.consumer(setup.tenant.id, "consumer-1", "alice")
                alice.poll()

                val req1 = submit(setup)
                controllable.complete(req1)
                val req2 = submit(setup)
                controllable.complete(req2)

                val firstPoll = alice.poll()
                val secondPoll = alice.poll() // no ack between

                assertThat(firstPoll.rows.map { it.requestId })
                    .containsAll(listOf(req1.id, req2.id))
                assertThat(secondPoll.rows.map { it.requestId })
                    .`as`("without ack, the same rows should be redelivered")
                    .containsAll(listOf(req1.id, req2.id))
            }
        }
    }

    @Test
    fun `acking advances the cursor — next poll returns nothing`() {
        scenario {
            given { provisionScenario() }.whenever { setup ->
                val alice = consumers.consumer(setup.tenant.id, "consumer-1", "alice")
                alice.poll()

                val req = submit(setup)
                controllable.complete(req)
                val first = alice.poll()
                assertThat(first.rows).hasSize(1)
                val seq = first.lastSequence!!

                val second = alice.poll(acknowledgeUpTo = seq)
                assertThat(second.rows).isEmpty()
            }
        }
    }

    @Test
    fun `partial ack — process some, ack lower sequence, get the rest next time`() {
        scenario {
            given { provisionScenario() }.whenever { setup ->
                val alice = consumers.consumer(setup.tenant.id, "consumer-1", "alice")
                alice.poll()

                val req1 = submit(setup)
                controllable.complete(req1)
                val req2 = submit(setup)
                controllable.complete(req2)
                val req3 = submit(setup)
                controllable.complete(req3)

                val first = alice.poll()
                assertThat(first.rows).hasSize(3)
                val seqs = first.rows.map { it.sequence }
                // Ack only up through the second sequence.
                val partialAck = seqs[1]

                val second = alice.poll(acknowledgeUpTo = partialAck)
                // Third row redelivered.
                assertThat(second.rows.map { it.sequence }).containsExactly(seqs[2])
            }
        }
    }
}

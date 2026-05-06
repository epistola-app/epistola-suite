package app.epistola.suite.generation.collect.scenarios

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Two distinct consumers (i.e. two different X-API-Keys) on the same tenant
 * each see EVERY result, with independent cursor state. Use case: a tenant
 * with two unrelated downstream systems both pulling generations.
 *
 * This is the property documented in plan-suite.md and the design notes:
 * cross-consumer there is no exclusion — only within ONE consumer is there
 * a single logical view of the queue.
 */
class MultiConsumerSameTenantIT : CollectScenarioTestBase() {

    @Test
    fun `two consumers each see every result and ack independently`() {
        scenario {
            given { provisionScenario() }.whenever { setup ->
                val a = consumers.consumer(setup.tenant.id, "consumer-A", "node-a")
                val b = consumers.consumer(setup.tenant.id, "consumer-B", "node-b")

                val req1 = submit(setup, routingKey = "k-1")
                val req2 = submit(setup, routingKey = "k-2")
                val req3 = submit(setup, routingKey = "k-3")
                controllable.complete(req1)
                controllable.complete(req2)
                controllable.complete(req3)

                // Both consumers see all three rows on their first poll.
                val pageA = a.poll()
                val pageB = b.poll()
                assertThat(pageA.rows.map { it.requestId }).containsAll(listOf(req1.id, req2.id, req3.id))
                assertThat(pageB.rows.map { it.requestId }).containsAll(listOf(req1.id, req2.id, req3.id))

                // Consumer A acks all three. Consumer B's cursor is independent —
                // it should still see them on its next poll.
                val pageA2 = a.poll(acknowledgeUpTo = pageA.lastSequence)
                val pageB2 = b.poll() // no ack from B yet

                assertThat(pageA2.rows).isEmpty()
                assertThat(pageB2.rows.map { it.requestId }).containsAll(listOf(req1.id, req2.id, req3.id))
            }
        }
    }
}

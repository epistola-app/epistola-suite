package app.epistola.suite.generation.collect.commands

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class AcknowledgeGenerationResultsHandlerIT : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    private fun tenant() = TenantKey.of("t-${UUID.randomUUID().toString().take(8)}")
    private fun consumerId() = "cons-${UUID.randomUUID()}"

    /**
     * Read cursors directly out of `consumer_partition_cursors`. Partitions
     * with no row default to 0 (the same fallback the production fetch path
     * applies via UNNEST).
     */
    private fun cursorsFor(t: TenantKey, c: String, partitions: Set<Int>): Map<Int, Long> {
        if (partitions.isEmpty()) return emptyMap()
        val rows = jdbi.withHandle<Map<Int, Long>, Exception> { h ->
            h.createQuery(
                """
                SELECT partition, last_acked_sequence
                FROM consumer_partition_cursors
                WHERE tenant_key = :t AND consumer_id = :c AND partition = ANY(:p::int[])
                """,
            )
                .bind("t", t)
                .bind("c", c)
                .bindArray("p", Int::class.javaObjectType, *partitions.toTypedArray())
                .map { rs, _ -> rs.getInt("partition") to rs.getLong("last_acked_sequence") }
                .toMap()
        }
        return partitions.associateWith { rows[it] ?: 0L }
    }

    @Test
    fun `advances cursors for every assigned partition to acknowledgeUpTo`() {
        val t = tenant()
        val c = consumerId()

        withMediator {
            AcknowledgeGenerationResults(
                tenantId = t,
                consumerId = c,
                partitions = setOf(0, 7, 33),
                acknowledgeUpTo = 500L,
            ).execute()
        }

        val read = cursorsFor(t, c, setOf(0, 7, 33, 1))
        assertThat(read).containsEntry(0, 500L).containsEntry(7, 500L).containsEntry(33, 500L)
        // Partition 1 was not in the ack — cursor stays at default 0.
        assertThat(read[1]).isEqualTo(0L)
    }

    @Test
    fun `late ack cannot move a cursor backwards`() {
        val t = tenant()
        val c = consumerId()

        withMediator { AcknowledgeGenerationResults(t, c, setOf(0), 1000L).execute() }
        withMediator { AcknowledgeGenerationResults(t, c, setOf(0), 500L).execute() }

        assertThat(cursorsFor(t, c, setOf(0))[0]).isEqualTo(1000L)
    }

    @Test
    fun `empty partition set is a no-op`() {
        val t = tenant()
        val c = consumerId()

        withMediator { AcknowledgeGenerationResults(t, c, emptySet(), 500L).execute() }

        // No row exists; default cursor returned.
        assertThat(cursorsFor(t, c, setOf(0))[0]).isEqualTo(0L)
    }

    @Test
    fun `acknowledgeUpTo of zero is a no-op`() {
        val t = tenant()
        val c = consumerId()

        withMediator { AcknowledgeGenerationResults(t, c, setOf(0, 1, 2), 0L).execute() }

        val read = cursorsFor(t, c, setOf(0, 1, 2))
        assertThat(read.values).allMatch { it == 0L }
    }

    @Test
    fun `cursors are isolated per (tenant, consumer)`() {
        val tA = tenant()
        val tB = tenant()
        val cA = consumerId()
        val cB = consumerId()

        withMediator { AcknowledgeGenerationResults(tA, cA, setOf(0), 100L).execute() }
        withMediator { AcknowledgeGenerationResults(tB, cA, setOf(0), 200L).execute() } // same consumer, diff tenant
        withMediator { AcknowledgeGenerationResults(tA, cB, setOf(0), 300L).execute() } // same tenant, diff consumer

        assertThat(cursorsFor(tA, cA, setOf(0))[0]).isEqualTo(100L)
        assertThat(cursorsFor(tB, cA, setOf(0))[0]).isEqualTo(200L)
        assertThat(cursorsFor(tA, cB, setOf(0))[0]).isEqualTo(300L)
    }
}

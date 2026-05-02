package app.epistola.suite.generation.collect.persistence

import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Repository

@Repository
class JdbiConsumerPartitionCursorRepository(
    private val jdbi: Jdbi,
) : ConsumerPartitionCursorRepository {

    override fun cursorsFor(tenantKey: TenantKey, consumerId: String, partitions: Set<Int>): Map<Int, Long> {
        if (partitions.isEmpty()) return emptyMap()
        val rows = jdbi.withHandle<Map<Int, Long>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT partition, last_acked_sequence
                FROM consumer_partition_cursors
                WHERE tenant_key = :tenantKey
                  AND consumer_id = :consumerId
                  AND partition = ANY(:partitions::int[])
                """,
            )
                .bind("tenantKey", tenantKey)
                .bind("consumerId", consumerId)
                .bindArray("partitions", Int::class.javaObjectType, *partitions.toTypedArray())
                .map { rs, _ -> rs.getInt("partition") to rs.getLong("last_acked_sequence") }
                .toMap()
        }
        return partitions.associateWith { rows[it] ?: 0L }
    }

    override fun advance(tenantKey: TenantKey, consumerId: String, advances: Map<Int, Long>) {
        if (advances.isEmpty()) return
        jdbi.useHandle<Exception> { handle ->
            // GREATEST guarantees we never go backwards even if two callers race.
            // ON CONFLICT keeps the operation idempotent with respect to first-time
            // inserts vs subsequent updates.
            val batch = handle.prepareBatch(
                """
                INSERT INTO consumer_partition_cursors (tenant_key, consumer_id, partition, last_acked_sequence, updated_at)
                VALUES (:tenantKey, :consumerId, :partition, :seq, NOW())
                ON CONFLICT (tenant_key, consumer_id, partition) DO UPDATE
                SET last_acked_sequence = GREATEST(consumer_partition_cursors.last_acked_sequence, EXCLUDED.last_acked_sequence),
                    updated_at = NOW()
                """,
            )
            for ((partition, seq) in advances) {
                batch.bind("tenantKey", tenantKey)
                    .bind("consumerId", consumerId)
                    .bind("partition", partition)
                    .bind("seq", seq)
                    .add()
            }
            batch.execute()
        }
    }
}

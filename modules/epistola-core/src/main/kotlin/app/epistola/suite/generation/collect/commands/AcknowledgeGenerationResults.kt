package app.epistola.suite.generation.collect.commands

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Advance the per-(consumer, partition) ack cursors for a consumer.
 *
 * Per the contract spec: the client sends `acknowledgeUpTo: <sequence>` on
 * its next `/generation/collect` call to confirm it processed everything up
 * to that sequence. The server advances the cursor for each of the consumer's
 * currently-assigned partitions to that value (clamped via GREATEST so a late
 * ack never moves a cursor backwards).
 *
 * Empty [partitions] or non-positive [acknowledgeUpTo] are no-ops — useful so
 * the collect endpoint can call this unconditionally without branching.
 */
data class AcknowledgeGenerationResults(
    val tenantId: TenantKey,
    val consumerId: String,
    val partitions: Set<Int>,
    val acknowledgeUpTo: Long,
) : Command<Unit>,
    RequiresPermission {
    override val permission get() = Permission.DOCUMENT_GENERATE
    override val tenantKey get() = tenantId
}

@Component
class AcknowledgeGenerationResultsHandler(
    private val jdbi: Jdbi,
) : CommandHandler<AcknowledgeGenerationResults, Unit> {

    override fun handle(command: AcknowledgeGenerationResults) {
        if (command.partitions.isEmpty() || command.acknowledgeUpTo <= 0L) return
        // GREATEST guarantees we never go backwards even if two callers race.
        // ON CONFLICT keeps the operation idempotent with respect to first-time
        // inserts vs subsequent updates.
        jdbi.useHandle<Exception> { handle ->
            val batch = handle.prepareBatch(
                """
                INSERT INTO consumer_partition_cursors (tenant_key, consumer_id, partition, last_acked_sequence, updated_at)
                VALUES (:tenantKey, :consumerId, :partition, :seq, NOW())
                ON CONFLICT (tenant_key, consumer_id, partition) DO UPDATE
                SET last_acked_sequence = GREATEST(consumer_partition_cursors.last_acked_sequence, EXCLUDED.last_acked_sequence),
                    updated_at = NOW()
                """,
            )
            for (partition in command.partitions) {
                batch.bind("tenantKey", command.tenantId)
                    .bind("consumerId", command.consumerId)
                    .bind("partition", partition)
                    .bind("seq", command.acknowledgeUpTo)
                    .add()
            }
            batch.execute()
        }
    }
}

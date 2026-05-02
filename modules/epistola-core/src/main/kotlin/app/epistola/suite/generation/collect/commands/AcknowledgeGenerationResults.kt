package app.epistola.suite.generation.collect.commands

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.generation.collect.persistence.ConsumerPartitionCursorRepository
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
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
    private val cursors: ConsumerPartitionCursorRepository,
) : CommandHandler<AcknowledgeGenerationResults, Unit> {

    override fun handle(command: AcknowledgeGenerationResults) {
        if (command.partitions.isEmpty() || command.acknowledgeUpTo <= 0L) return
        // Same target sequence for every partition we own. The repository's
        // GREATEST semantics ensure no cursor moves backwards.
        val advances = command.partitions.associateWith { command.acknowledgeUpTo }
        cursors.advance(command.tenantId, command.consumerId, advances)
    }
}

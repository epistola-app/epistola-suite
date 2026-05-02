package app.epistola.suite.generation.collect.commands

import app.epistola.suite.common.ids.DocumentKey
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.generation.collect.domain.GenerationResultRow
import app.epistola.suite.generation.collect.domain.Partition
import app.epistola.suite.generation.collect.domain.ResultStatus
import app.epistola.suite.generation.collect.persistence.GenerationResultRepository
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Emit a terminal generation result into `generation_results` so collectors can
 * pick it up. Called by [DocumentGenerationExecutor][app.epistola.suite.documents.batch.DocumentGenerationExecutor]
 * when a request transitions to [ResultStatus.COMPLETED] or [ResultStatus.FAILED].
 *
 * Routing-key fallback: if [DocumentGenerationRequest.routingKey] is null, the
 * request id is used as the routing key (per spec: *"if no routing key was
 * provided, the request id is used"*). This guarantees every result lands on
 * exactly one partition determined by [Partition.partitionFor].
 *
 * SystemInternal — the executor calls this from a background worker, not in
 * response to a user request, so there is no principal to authorize against.
 */
data class EmitGenerationResult(
    val request: DocumentGenerationRequest,
    val status: ResultStatus,
    val documentId: DocumentKey?,
    val sizeBytes: Long?,
    val contentType: String?,
    val error: String?,
    val completedAt: OffsetDateTime,
) : Command<GenerationResultRow>,
    SystemInternal {
    init {
        require((status == ResultStatus.COMPLETED) == (documentId != null)) {
            "documentId must be set iff status == COMPLETED"
        }
        require((status == ResultStatus.FAILED) == (error != null)) {
            "error must be set iff status == FAILED"
        }
    }
}

@Component
class EmitGenerationResultHandler(
    private val repository: GenerationResultRepository,
) : CommandHandler<EmitGenerationResult, GenerationResultRow> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: EmitGenerationResult): GenerationResultRow {
        val req = command.request
        // Fallback per spec: routingKey ?: requestId. Both are stable for the lifetime
        // of the request, so the partition is deterministic — repeat emits (idempotency
        // not enforced here, but theoretically allowed) land on the same partition.
        val routingKey = req.routingKey ?: req.id.value.toString()
        val partition = Partition.partitionFor(routingKey)

        val placeholderTimestamp = OffsetDateTime.now()
        val row = GenerationResultRow(
            sequence = 0L, // assigned by BIGSERIAL
            partition = partition,
            createdAt = placeholderTimestamp, // overwritten by DEFAULT NOW()
            requestId = req.id,
            batchId = req.batchId,
            tenantKey = req.tenantKey,
            routingKey = routingKey,
            status = command.status,
            documentId = command.documentId,
            correlationId = req.correlationKey,
            templateId = req.templateKey,
            variantId = req.variantKey,
            versionId = req.versionKey,
            filename = req.filename,
            contentType = command.contentType,
            sizeBytes = command.sizeBytes,
            error = command.error,
            completedAt = command.completedAt,
        )

        val inserted = repository.append(row)
        logger.debug(
            "Emitted generation result requestId={} partition={} sequence={} status={}",
            req.id.value,
            partition,
            inserted.sequence,
            command.status,
        )
        return inserted
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.collect.commands

import app.epistola.suite.common.NotEventLogged
import app.epistola.suite.common.ids.DocumentKey
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.generation.collect.domain.GenerationResultRow
import app.epistola.suite.generation.collect.domain.Partition
import app.epistola.suite.generation.collect.domain.ResultStatus
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
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
    SystemInternal,
    NotEventLogged {
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
    private val jdbi: Jdbi,
) : CommandHandler<EmitGenerationResult, GenerationResultRow> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: EmitGenerationResult): GenerationResultRow {
        val req = command.request
        // Fallback per spec: routingKey ?: requestId. Both are stable for the lifetime
        // of the request, so the partition is deterministic — repeat emits (idempotency
        // not enforced here, but theoretically allowed) land on the same partition.
        val routingKey = req.routingKey ?: req.id.value.toString()
        val partition = Partition.partitionFor(routingKey)

        val inserted = jdbi.withHandle<GenerationResultRow, Exception> { handle ->
            handle.createQuery(
                """
                INSERT INTO generation_results (
                    partition, request_id, batch_id, tenant_key, routing_key, status,
                    document_id, correlation_id, template_id, variant_id, version_id,
                    filename, content_type, size_bytes, error, completed_at
                )
                VALUES (
                    :partition, :requestId, :batchId, :tenantKey, :routingKey, :status,
                    :documentId, :correlationId, :templateId, :variantId, :versionId,
                    :filename, :contentType, :sizeBytes, :error, :completedAt
                )
                RETURNING sequence, partition, created_at, request_id, batch_id, tenant_key,
                          routing_key, status, document_id, correlation_id, template_id,
                          variant_id, version_id, filename, content_type, size_bytes,
                          error, completed_at
                """,
            )
                .bind("partition", partition)
                .bind("requestId", req.id.value)
                .bind("batchId", req.batchId?.value)
                .bind("tenantKey", req.tenantKey)
                .bind("routingKey", routingKey)
                .bind("status", command.status.name)
                .bind("documentId", command.documentId?.value)
                .bind("correlationId", req.correlationId)
                .bind("templateId", req.templateKey)
                .bind("variantId", req.variantKey)
                .bind("versionId", req.versionKey)
                .bind("filename", req.filename)
                .bind("contentType", command.contentType)
                .bind("sizeBytes", command.sizeBytes)
                .bind("error", command.error)
                .bind("completedAt", command.completedAt)
                .mapTo<GenerationResultRow>()
                .one()
        }
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

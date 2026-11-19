// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.commands

import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.DocumentVisibility
import app.epistola.suite.documents.GenerationJobNotCancellableException
import app.epistola.suite.documents.GenerationJobNotFoundException
import app.epistola.suite.documents.model.CancelReason
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.model.GENERATION_REQUEST_COLUMNS
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.generation.collect.commands.EmitGenerationResult
import app.epistola.suite.generation.collect.domain.ResultStatus
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Command to cancel a generation job that is waiting for input, pending or in progress. It records
 * [CancelReason.CALLER]; a job created with `interactive` also gets a `cancelled` collect result
 * (contract ADR 0011), which a job without a form never did and still does not.
 *
 * @property tenantId Tenant that owns the job
 * @property requestId The generation request ID to cancel
 */
data class CancelGenerationJob(
    val tenantId: TenantKey,
    val requestId: GenerationRequestKey,
) : Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.DOCUMENT_GENERATE
    override val tenantKey get() = tenantId
}

@Component
class CancelGenerationJobHandler(
    private val jdbi: Jdbi,
    private val mediator: Mediator,
) : CommandHandler<CancelGenerationJob, Boolean> {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(command: CancelGenerationJob): Boolean {
        log.info("Cancelling generation job request={} tenant={}", command.requestId, command.tenantId)

        // Another application's job is not found, as another tenant's is (ADR 0054). The check
        // below guards the update: both run in this transaction.
        val visibility = DocumentVisibility.current()
        return jdbi.inTransaction<Boolean, Exception> { handle ->
            // 1. Verify request exists and belongs to tenant, and can be cancelled
            val status = handle.createQuery(
                """
                SELECT status
                FROM document_generation_requests
                WHERE id = :requestId AND tenant_key = :tenantId
                ${visibility.predicate("application_id")}
                """,
            )
                .bind("requestId", command.requestId)
                .bind("tenantId", command.tenantId)
                .let(visibility::bindTo)
                .mapTo(String::class.java)
                .findOne()
                .orElse(null)
                ?: throw GenerationJobNotFoundException(command.tenantId, command.requestId)

            val requestStatus = RequestStatus.valueOf(status)

            // 2. Check if request can be cancelled
            if (requestStatus !in CANCELLABLE) {
                log.warn("Request cannot be cancelled request={} status={}", command.requestId, requestStatus)
                throw GenerationJobNotCancellableException(
                    command.tenantId,
                    command.requestId,
                    "Status is $requestStatus",
                )
            }

            // 3. Mark request as cancelled
            val cancelled = handle.createQuery(
                """
                UPDATE document_generation_requests
                SET status = :status,
                    cancel_reason = :cancelReason,
                    completed_at = NOW()
                WHERE id = :requestId
                  AND tenant_key = :tenantId
                  AND status IN ('AWAITING_INPUT', 'PENDING', 'IN_PROGRESS')
                  ${visibility.predicate("application_id")}
                RETURNING $GENERATION_REQUEST_COLUMNS
                """,
            )
                .bind("requestId", command.requestId)
                .bind("tenantId", command.tenantId)
                .let(visibility::bindTo)
                .bind("status", RequestStatus.CANCELLED.name)
                .bind("cancelReason", CancelReason.CALLER.name)
                .mapTo<DocumentGenerationRequest>()
                .findOne()
                .orElse(null)

            if (cancelled == null) {
                log.warn("Request was already completed or cancelled request={}", command.requestId)
                throw GenerationJobNotCancellableException(
                    command.tenantId,
                    command.requestId,
                    "Job was already completed or cancelled",
                )
            }

            log.info("Cancelled generation job request={}", command.requestId)
            emitCancelledIfInteractive(handle, mediator, cancelled, CancelReason.CALLER)
            true
        }
    }
}

private val CANCELLABLE = setOf(RequestStatus.AWAITING_INPUT, RequestStatus.PENDING, RequestStatus.IN_PROGRESS)

/**
 * Emits the `cancelled` collect result of [request] when it was created with `interactive`
 * (contract ADR 0011: a caller that only collects must learn that the form was abandoned). A job
 * without a form gets none, as before. In the cancelling transaction, so the result exists exactly
 * when the cancellation does.
 */
internal fun emitCancelledIfInteractive(handle: Handle, mediator: Mediator, request: DocumentGenerationRequest, reason: CancelReason) {
    val interactive = handle.createQuery("SELECT 1 FROM generation_request_interactions WHERE request_id = :id AND created_at = :createdAt")
        .bind("id", request.id)
        .bind("createdAt", request.createdAt)
        .mapTo<Int>()
        .findOne()
        .isPresent
    if (!interactive) return
    mediator.send(
        EmitGenerationResult(
            request = request,
            status = ResultStatus.CANCELLED,
            documentId = null,
            sizeBytes = null,
            contentType = null,
            error = null,
            completedAt = EpistolaClock.offsetDateTime(),
            cancelReason = reason,
        ),
    )
}

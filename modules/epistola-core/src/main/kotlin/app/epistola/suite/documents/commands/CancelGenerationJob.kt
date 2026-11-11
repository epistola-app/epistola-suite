// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.commands

import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.DocumentVisibility
import app.epistola.suite.documents.GenerationJobNotCancellableException
import app.epistola.suite.documents.GenerationJobNotFoundException
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Command to cancel a pending or in-progress generation job.
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
            if (requestStatus !in setOf(RequestStatus.PENDING, RequestStatus.IN_PROGRESS)) {
                log.warn("Request cannot be cancelled request={} status={}", command.requestId, requestStatus)
                throw GenerationJobNotCancellableException(
                    command.tenantId,
                    command.requestId,
                    "Status is $requestStatus",
                )
            }

            // 3. Mark request as cancelled
            val updated = handle.createUpdate(
                """
                UPDATE document_generation_requests
                SET status = :status,
                    completed_at = NOW()
                WHERE id = :requestId
                  AND tenant_key = :tenantId
                  AND status IN ('PENDING', 'IN_PROGRESS')
                  ${visibility.predicate("application_id")}
                """,
            )
                .bind("requestId", command.requestId)
                .bind("tenantId", command.tenantId)
                .let(visibility::bindTo)
                .bind("status", RequestStatus.CANCELLED.name)
                .execute()

            if (updated == 0) {
                log.warn("Request was already completed or cancelled request={}", command.requestId)
                throw GenerationJobNotCancellableException(
                    command.tenantId,
                    command.requestId,
                    "Job was already completed or cancelled",
                )
            }

            log.info("Cancelled generation job request={}", command.requestId)
            true
        }
    }
}

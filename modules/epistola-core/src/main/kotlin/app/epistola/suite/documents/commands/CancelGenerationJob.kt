package app.epistola.suite.documents.commands

import app.epistola.suite.common.ids.GenerationRequestId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
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
    val tenantId: TenantId,
    val requestId: GenerationRequestId,
) : Command<Boolean>

@Component
class CancelGenerationJobHandler(
    private val jdbi: Jdbi,
) : CommandHandler<CancelGenerationJob, Boolean> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: CancelGenerationJob): Boolean {
        logger.info("Cancelling generation job {} for tenant {}", command.requestId, command.tenantId)

        return jdbi.inTransaction<Boolean, Exception> { handle ->
            // 1. Verify request exists and belongs to tenant, and can be cancelled
            val status = handle.createQuery(
                """
                SELECT status
                FROM document_generation_requests
                WHERE id = :requestId AND tenant_id = :tenantId
                """,
            )
                .bind("requestId", command.requestId)
                .bind("tenantId", command.tenantId)
                .mapTo(String::class.java)
                .findOne()
                .orElse(null)

            if (status == null) {
                logger.warn("Request {} not found for tenant {}", command.requestId, command.tenantId)
                return@inTransaction false
            }

            val requestStatus = RequestStatus.valueOf(status)

            // 2. Check if request can be cancelled
            if (requestStatus !in setOf(RequestStatus.PENDING, RequestStatus.IN_PROGRESS)) {
                logger.warn("Request {} cannot be cancelled (status: {})", command.requestId, requestStatus)
                return@inTransaction false
            }

            // 3. Mark request as cancelled
            val updated = handle.createUpdate(
                """
                UPDATE document_generation_requests
                SET status = :status,
                    completed_at = NOW()
                WHERE id = :requestId
                  AND tenant_id = :tenantId
                  AND status IN ('PENDING', 'IN_PROGRESS')
                """,
            )
                .bind("requestId", command.requestId)
                .bind("tenantId", command.tenantId)
                .bind("status", RequestStatus.CANCELLED.name)
                .execute()

            if (updated > 0) {
                logger.info("Cancelled generation job {}", command.requestId)
                true
            } else {
                logger.warn("Request {} was already completed or cancelled", command.requestId)
                false
            }
        }
    }
}

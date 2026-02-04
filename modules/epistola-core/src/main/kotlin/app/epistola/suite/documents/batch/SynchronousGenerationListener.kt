package app.epistola.suite.documents.batch

import app.epistola.suite.common.ids.GenerationRequestId
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.model.RequestStatus
import jakarta.annotation.PostConstruct
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Synchronous document generation listener for test environments.
 *
 * When enabled, this listener executes generation jobs immediately after creation,
 * eliminating the need for polling and Awaitility waits in tests.
 *
 * Only active when `epistola.generation.synchronous=true`.
 */
@Component
@ConditionalOnProperty(
    name = ["epistola.generation.synchronous"],
    havingValue = "true",
    matchIfMissing = false,
)
class SynchronousGenerationListener(
    private val jdbi: Jdbi,
    private val jobExecutor: DocumentGenerationExecutor,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun init() {
        logger.info("SynchronousGenerationListener initialized - jobs will execute immediately")
    }

    /**
     * Handle generation request created events.
     *
     * The event is published after the JDBI transaction commits, so the data is
     * already persisted and visible.
     */
    @EventListener
    fun onGenerationRequestCreated(event: GenerationRequestCreatedEvent) {
        val requestId = event.request.id
        logger.info("Synchronous execution: processing request {} immediately", requestId.value)

        try {
            // Claim and execute the request
            val request = claimRequest(requestId)
            if (request != null) {
                jobExecutor.execute(request)
            } else {
                logger.warn("Request {} was already claimed or not in PENDING state", requestId.value)
            }
        } catch (e: Exception) {
            logger.error("Synchronous execution failed for request {}: {}", requestId.value, e.message, e)
            markRequestFailed(requestId, e.message)
        }
    }

    /**
     * Claim a request by setting it to IN_PROGRESS.
     */
    private fun claimRequest(requestId: GenerationRequestId): DocumentGenerationRequest? = jdbi.inTransaction<DocumentGenerationRequest?, Exception> { handle ->
        handle.createQuery(
            """
                UPDATE document_generation_requests
                SET status = 'IN_PROGRESS',
                    claimed_by = 'synchronous',
                    claimed_at = NOW(),
                    started_at = NOW()
                WHERE id = :requestId
                  AND status = 'PENDING'
                RETURNING id, tenant_id, job_type, status, claimed_by, claimed_at,
                          total_count, completed_count, failed_count, error_message,
                          created_at, started_at, completed_at, expires_at
                """,
        )
            .bind("requestId", requestId)
            .mapTo<DocumentGenerationRequest>()
            .findOne()
            .orElse(null)
    }

    /**
     * Mark a request as failed when execution throws an exception.
     */
    private fun markRequestFailed(requestId: GenerationRequestId, errorMessage: String?) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE document_generation_requests
                SET status = :status,
                    error_message = :errorMessage,
                    completed_at = NOW()
                WHERE id = :requestId
                """,
            )
                .bind("requestId", requestId)
                .bind("status", RequestStatus.FAILED.name)
                .bind("errorMessage", errorMessage?.take(1000))
                .execute()
        }
    }
}

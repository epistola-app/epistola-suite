package app.epistola.suite.documents.model

import java.time.OffsetDateTime
import java.util.UUID

/**
 * A document generation request (single or batch).
 *
 * Tracks the overall status of a generation job. For batch jobs, individual items
 * are tracked in [DocumentGenerationItem].
 *
 * @property id Unique request identifier (UUID)
 * @property tenantId Tenant that submitted this request
 * @property jobType Type of job (SINGLE or BATCH)
 * @property status Current status of the request
 * @property claimedBy Instance identifier (hostname-pid) that claimed this job
 * @property claimedAt When the job was claimed by an instance
 * @property totalCount Total number of items to generate
 * @property completedCount Number of items successfully completed
 * @property failedCount Number of items that failed
 * @property errorMessage Error message if the request failed
 * @property createdAt When the request was created
 * @property startedAt When processing started
 * @property completedAt When processing completed (success or failure)
 * @property expiresAt When this request should be cleaned up
 */
data class DocumentGenerationRequest(
    val id: UUID,
    val tenantId: UUID,
    val jobType: JobType,
    val status: RequestStatus,
    val claimedBy: String?,
    val claimedAt: OffsetDateTime?,
    val totalCount: Int,
    val completedCount: Int,
    val failedCount: Int,
    val errorMessage: String?,
    val createdAt: OffsetDateTime,
    val startedAt: OffsetDateTime?,
    val completedAt: OffsetDateTime?,
    val expiresAt: OffsetDateTime?,
) {
    /**
     * Calculate progress percentage.
     */
    val progressPercentage: Double
        get() = if (totalCount > 0) {
            ((completedCount + failedCount).toDouble() / totalCount) * 100.0
        } else {
            0.0
        }

    /**
     * Check if the request is in a terminal state (cannot be modified).
     */
    val isTerminal: Boolean
        get() = status in setOf(RequestStatus.COMPLETED, RequestStatus.FAILED, RequestStatus.CANCELLED)

    /**
     * Check if the request can be cancelled.
     */
    val isCancellable: Boolean
        get() = status in setOf(RequestStatus.PENDING, RequestStatus.IN_PROGRESS)
}

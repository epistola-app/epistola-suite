package app.epistola.suite.documents.model

import app.epistola.suite.common.ids.BatchId
import app.epistola.suite.common.ids.TenantId
import java.time.OffsetDateTime

/**
 * Metadata for a batch of document generation requests.
 *
 * Tracks aggregated counts and completion status for a group of related requests.
 * Updated as individual requests complete.
 *
 * @property id Unique batch identifier (UUID)
 * @property tenantId Tenant that submitted this batch
 * @property totalCount Total number of requests in this batch
 * @property completedCount Number of requests successfully completed
 * @property failedCount Number of requests that failed
 * @property createdAt When the batch was created
 * @property completedAt When all requests in the batch completed (success or failure)
 */
data class DocumentGenerationBatch(
    val id: BatchId,
    val tenantId: TenantId,
    val totalCount: Int,
    val completedCount: Int,
    val failedCount: Int,
    val createdAt: OffsetDateTime,
    val completedAt: OffsetDateTime?,
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
     * Check if the batch is complete (all requests finished).
     */
    val isComplete: Boolean
        get() = (completedCount + failedCount) >= totalCount

    /**
     * Check if the batch had any failures.
     */
    val hasFailures: Boolean
        get() = failedCount > 0

    /**
     * Get pending count (not yet completed or failed).
     */
    val pendingCount: Int
        get() = totalCount - completedCount - failedCount
}

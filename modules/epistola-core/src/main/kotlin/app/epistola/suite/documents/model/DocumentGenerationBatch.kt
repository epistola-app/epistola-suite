package app.epistola.suite.documents.model

import app.epistola.suite.common.ids.BatchKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.json.Json
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
 * @property assemblyStatus Status of batch download assembly
 * @property downloadFormats Requested download formats for batch assembly
 * @property createdAt When the batch was created
 * @property completedAt When all requests in the batch completed (success or failure)
 */
data class DocumentGenerationBatch(
    val id: BatchKey,
    val tenantKey: TenantKey,
    val totalCount: Int,
    val completedCount: Int,
    val failedCount: Int,
    val assemblyStatus: AssemblyStatus = AssemblyStatus.NONE,
    @Json val downloadFormats: List<BatchDownloadFormat> = emptyList(),
    @Json val downloadParts: Map<String, List<DownloadPartInfo>> = emptyMap(),
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

package app.epistola.suite.loadtest.model

import app.epistola.suite.common.ids.DocumentId
import java.time.OffsetDateTime

/**
 * An individual document generation request within a load test run.
 *
 * These records are used for detailed debugging and analysis.
 * They are automatically cleaned up after 7 days to conserve storage.
 *
 * @property id Unique request identifier
 * @property runId Load test run this request belongs to
 * @property sequenceNumber 1-based sequence number within the test run
 * @property startedAt When the request started
 * @property completedAt When the request completed (null if still in progress)
 * @property durationMs Request duration in milliseconds (null if still in progress)
 * @property success Whether the request completed successfully
 * @property errorMessage Error message if the request failed
 * @property errorType Classified error type (e.g., "VALIDATION", "TIMEOUT", "GENERATION")
 * @property documentId Reference to the generated document (deleted after test completes)
 */
data class LoadTestRequest(
    val id: LoadTestRequestId,
    val runId: LoadTestRunId,
    val sequenceNumber: Int,
    val startedAt: OffsetDateTime,
    val completedAt: OffsetDateTime?,
    val durationMs: Long?,
    val success: Boolean,
    val errorMessage: String?,
    val errorType: String?,
    val documentId: DocumentId?,
) {
    init {
        require(sequenceNumber > 0) {
            "Sequence number must be positive, got $sequenceNumber"
        }
        require((completedAt != null && durationMs != null) || (completedAt == null && durationMs == null)) {
            "completedAt and durationMs must both be set or both be null"
        }
    }
}

package app.epistola.suite.documents.batch

import app.epistola.suite.common.ids.BatchKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.model.DocumentGenerationRequest

/**
 * Event published after a document generation request has been committed in
 * `PENDING` status.
 *
 * [GenerationRequestDrainListener] reacts by nudging the [JobPoller] to drain
 * immediately, so enqueued jobs start without waiting for the scheduled
 * fallback poll.
 */
data class GenerationRequestCreatedEvent(
    val request: DocumentGenerationRequest,
)

/**
 * Batch sibling of [GenerationRequestCreatedEvent]: published once after a
 * whole batch of generation requests has been committed.
 */
data class GenerationBatchCreatedEvent(
    val batchId: BatchKey,
    val tenantKey: TenantKey,
)

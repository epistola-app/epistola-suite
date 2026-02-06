package app.epistola.suite.documents.model

import app.epistola.suite.common.ids.BatchId
import app.epistola.suite.common.ids.DocumentId
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.GenerationRequestId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import org.jdbi.v3.json.Json
import tools.jackson.databind.node.ObjectNode
import java.time.OffsetDateTime

/**
 * A document generation request.
 *
 * Each request represents a SINGLE document to generate.
 * Multiple requests can be grouped together using [batchId] for batch tracking.
 *
 * This flattened structure enables:
 * - True horizontal scaling (each request can be claimed independently by any instance)
 * - Simpler execution model (no item-level concurrency complexity)
 * - Better failure isolation (one failed document doesn't affect others)
 *
 * @property id Unique request identifier (UUIDv7)
 * @property batchId Optional batch identifier grouping related requests
 * @property tenantId Tenant that submitted this request
 * @property templateId Template to use for generation
 * @property variantId Variant of the template to use
 * @property versionId Explicit version ID (mutually exclusive with environmentId)
 * @property environmentId Environment to determine version from (mutually exclusive with versionId)
 * @property data JSON data to populate the template
 * @property filename Requested filename for the generated document
 * @property correlationId Client-provided ID for tracking documents across systems
 * @property documentId ID of the generated document (set when completed successfully)
 * @property status Current status of the request
 * @property claimedBy Instance identifier (hostname-pid) that claimed this job
 * @property claimedAt When the job was claimed by an instance
 * @property errorMessage Error message if the request failed
 * @property createdAt When the request was created
 * @property startedAt When processing started
 * @property completedAt When processing completed (success or failure)
 * @property expiresAt When this request should be cleaned up
 */
data class DocumentGenerationRequest(
    val id: GenerationRequestId,
    val batchId: BatchId?,
    val tenantId: TenantId,
    val templateId: TemplateId,
    val variantId: VariantId,
    val versionId: VersionId?,
    val environmentId: EnvironmentId?,
    @Json val data: ObjectNode,
    val filename: String?,
    val correlationId: String?,
    val documentId: DocumentId?,
    val status: RequestStatus,
    val claimedBy: String?,
    val claimedAt: OffsetDateTime?,
    val errorMessage: String?,
    val createdAt: OffsetDateTime,
    val startedAt: OffsetDateTime?,
    val completedAt: OffsetDateTime?,
    val expiresAt: OffsetDateTime?,
) {
    init {
        // Validate that exactly one of versionId or environmentId is set
        require((versionId != null) xor (environmentId != null)) {
            "Exactly one of versionId or environmentId must be set"
        }
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

    /**
     * Check if this request is part of a batch.
     */
    val isPartOfBatch: Boolean
        get() = batchId != null
}

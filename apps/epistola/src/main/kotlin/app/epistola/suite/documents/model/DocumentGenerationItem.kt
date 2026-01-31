package app.epistola.suite.documents.model

import org.jdbi.v3.json.Json
import tools.jackson.databind.node.ObjectNode
import java.time.OffsetDateTime
import java.util.UUID

/**
 * An individual item in a document generation batch.
 *
 * Each item represents one document to be generated. Items can succeed or fail
 * independently, allowing partial batch completion.
 *
 * @property id Unique item identifier (UUID)
 * @property requestId Parent generation request ID
 * @property templateId Template to use for generation
 * @property variantId Variant of the template to use
 * @property versionId Explicit version ID (mutually exclusive with environmentId)
 * @property environmentId Environment to determine version from (mutually exclusive with versionId)
 * @property data JSON data to populate the template
 * @property filename Requested filename for the generated document
 * @property correlationId Client-provided ID for tracking documents across systems
 * @property status Current status of this item
 * @property errorMessage Error message if processing failed
 * @property documentId ID of the generated document (if successful)
 * @property createdAt When the item was created
 * @property startedAt When processing started
 * @property completedAt When processing completed (success or failure)
 */
data class DocumentGenerationItem(
    val id: UUID,
    val requestId: UUID,
    val templateId: UUID,
    val variantId: UUID,
    val versionId: UUID?,
    val environmentId: UUID?,
    @Json val data: ObjectNode,
    val filename: String?,
    val correlationId: String?,
    val status: ItemStatus,
    val errorMessage: String?,
    val documentId: UUID?,
    val createdAt: OffsetDateTime,
    val startedAt: OffsetDateTime?,
    val completedAt: OffsetDateTime?,
) {
    init {
        // Validate that exactly one of versionId or environmentId is set
        require((versionId != null) xor (environmentId != null)) {
            "Exactly one of versionId or environmentId must be set"
        }
    }

    /**
     * Check if the item is in a terminal state.
     */
    val isTerminal: Boolean
        get() = status in setOf(ItemStatus.COMPLETED, ItemStatus.FAILED)
}

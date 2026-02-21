package app.epistola.suite.documents.model

import app.epistola.suite.common.ids.DocumentId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import java.time.OffsetDateTime

/**
 * A generated document's metadata.
 *
 * Binary content (PDF data) is stored in the pluggable [ContentStore] rather than
 * inline in the database. Use [ContentKey.document] to build the storage key.
 *
 * @property id Unique document identifier
 * @property tenantId Tenant that owns this document
 * @property templateId Template used to generate this document
 * @property variantId Variant of the template used
 * @property versionId Version of the template used
 * @property filename Original filename requested or generated
 * @property correlationId Client-provided ID for tracking documents across systems
 * @property contentType MIME type of the document (typically application/pdf)
 * @property sizeBytes Size of the document in bytes
 * @property createdAt When the document was generated
 * @property createdBy User who created this document
 */
data class Document(
    val id: DocumentId,
    val tenantId: TenantId,
    val templateId: TemplateId,
    val variantId: VariantId,
    val versionId: VersionId,
    val filename: String,
    val correlationId: String?,
    val contentType: String,
    val sizeBytes: Long,
    val createdAt: OffsetDateTime,
    val createdBy: UserId?,
)

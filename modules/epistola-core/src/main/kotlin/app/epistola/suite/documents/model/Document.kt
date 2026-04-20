package app.epistola.suite.documents.model

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.DocumentKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
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
    val id: DocumentKey,
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey = CatalogKey.DEFAULT,
    val templateKey: TemplateKey,
    val variantKey: VariantKey,
    val versionKey: VersionKey,
    val filename: String,
    val correlationId: String?,
    val contentType: String,
    val sizeBytes: Long,
    val createdAt: OffsetDateTime,
    val createdBy: UserKey?,
)

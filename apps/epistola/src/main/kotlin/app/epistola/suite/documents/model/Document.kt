package app.epistola.suite.documents.model

import java.time.OffsetDateTime

/**
 * A generated document stored in the database.
 *
 * Documents are stored as BYTEA in PostgreSQL. In the future, this may be migrated
 * to object storage (S3/MinIO) for better scalability.
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
 * @property content Binary content of the document (PDF data)
 * @property createdAt When the document was generated
 * @property createdBy User ID from Keycloak (future feature)
 */
data class Document(
    val id: Long,
    val tenantId: Long,
    val templateId: Long,
    val variantId: Long,
    val versionId: Long,
    val filename: String,
    val correlationId: String?,
    val contentType: String,
    val sizeBytes: Long,
    val content: ByteArray,
    val createdAt: OffsetDateTime,
    val createdBy: String?,
) {
    // Override equals/hashCode since ByteArray uses reference equality by default
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Document

        if (id != other.id) return false
        if (tenantId != other.tenantId) return false
        if (templateId != other.templateId) return false
        if (variantId != other.variantId) return false
        if (versionId != other.versionId) return false
        if (filename != other.filename) return false
        if (correlationId != other.correlationId) return false
        if (contentType != other.contentType) return false
        if (sizeBytes != other.sizeBytes) return false
        if (!content.contentEquals(other.content)) return false
        if (createdAt != other.createdAt) return false
        if (createdBy != other.createdBy) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + tenantId.hashCode()
        result = 31 * result + templateId.hashCode()
        result = 31 * result + variantId.hashCode()
        result = 31 * result + versionId.hashCode()
        result = 31 * result + filename.hashCode()
        result = 31 * result + (correlationId?.hashCode() ?: 0)
        result = 31 * result + contentType.hashCode()
        result = 31 * result + sizeBytes.hashCode()
        result = 31 * result + content.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (createdBy?.hashCode() ?: 0)
        return result
    }
}

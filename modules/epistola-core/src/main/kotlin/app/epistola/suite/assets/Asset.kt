package app.epistola.suite.assets

import app.epistola.suite.common.ids.AssetId
import app.epistola.suite.common.ids.TenantId
import java.time.OffsetDateTime

/**
 * Supported image media types for assets.
 */
enum class AssetMediaType(val mimeType: String) {
    PNG("image/png"),
    JPEG("image/jpeg"),
    SVG("image/svg+xml"),
    WEBP("image/webp"),
    ;

    companion object {
        private val BY_MIME_TYPE = entries.associateBy { it.mimeType }

        fun fromMimeType(mimeType: String): AssetMediaType = BY_MIME_TYPE[mimeType]
            ?: throw UnsupportedAssetTypeException(mimeType)
    }
}

/**
 * Asset metadata (excludes binary content to keep list queries lightweight).
 */
data class Asset(
    val id: AssetId,
    val tenantId: TenantId,
    val name: String,
    val mediaType: AssetMediaType,
    val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
    val createdAt: OffsetDateTime,
)

/**
 * Asset with binary content. Used for serving and PDF rendering.
 */
data class AssetContent(
    val id: AssetId,
    val tenantId: TenantId,
    val mediaType: AssetMediaType,
    val content: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AssetContent) return false
        return id == other.id && tenantId == other.tenantId
    }

    override fun hashCode(): Int = id.hashCode()
}

const val MAX_ASSET_SIZE_BYTES: Long = 5 * 1024 * 1024 // 5MB

class AssetNotFoundException(tenantId: TenantId, assetId: AssetId) : RuntimeException("Asset $assetId not found for tenant $tenantId")

class AssetTooLargeException(sizeBytes: Long) : RuntimeException("Asset size $sizeBytes bytes exceeds maximum of $MAX_ASSET_SIZE_BYTES bytes (5MB)")

class UnsupportedAssetTypeException(mimeType: String) : RuntimeException("Unsupported asset media type: $mimeType. Supported: ${AssetMediaType.entries.map { it.mimeType }}")

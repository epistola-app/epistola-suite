package app.epistola.suite.assets

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import java.time.OffsetDateTime

/** Broad behavioural class of an asset, derived from its media type prefix. */
enum class AssetMediaCategory { IMAGE, FONT, OTHER }

/**
 * An asset media type (MIME string).
 *
 * Open value class — *not* a closed enum. The set of *allowed* media types is
 * the seeded `asset_types` table (see [AssetTypeCatalog]); adding a type is a
 * DB insert, not a code change. The companion holds the well-known constants
 * code branches on; [category] gives the image/font behavioural split without
 * enumerating every type.
 */
@JvmInline
value class AssetMediaType(val mimeType: String) {
    val category: AssetMediaCategory
        get() = when {
            mimeType.startsWith("image/") -> AssetMediaCategory.IMAGE
            mimeType.startsWith("font/") -> AssetMediaCategory.FONT
            else -> AssetMediaCategory.OTHER
        }

    companion object {
        val PNG = AssetMediaType("image/png")
        val JPEG = AssetMediaType("image/jpeg")
        val SVG = AssetMediaType("image/svg+xml")
        val WEBP = AssetMediaType("image/webp")
        val TTF = AssetMediaType("font/ttf")
        val OTF = AssetMediaType("font/otf")

        /**
         * Wraps a MIME string. Does **not** validate — validation is against
         * the `asset_types` table at the write boundary ([AssetTypeCatalog]).
         * Safe for values read back from the DB (the FK guarantees validity).
         */
        fun fromMimeType(mimeType: String): AssetMediaType = AssetMediaType(mimeType)
    }
}

/**
 * Asset metadata (excludes binary content to keep list queries lightweight).
 */
data class Asset(
    val id: AssetKey,
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val catalogType: CatalogType = CatalogType.AUTHORED,
    val name: String,
    val mediaType: AssetMediaType,
    val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
    val createdAt: OffsetDateTime,
    val createdBy: UserKey? = null,
) {
    /**
     * Plain-[String] MIME accessor for Thymeleaf/SpringEL. Templates cannot read
     * [mediaType] directly: Kotlin mangles the value-class getter
     * (`getMediaType-<hash>()`), so SpringEL's `getMediaType()` lookup fails. This
     * non-value-class getter is reachable from EL.
     */
    val mediaTypeMime: String get() = mediaType.mimeType
}

/**
 * Asset with binary content. Used for serving and PDF rendering.
 */
data class AssetContent(
    val id: AssetKey,
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val mediaType: AssetMediaType,
    val content: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AssetContent) return false
        return id == other.id && tenantKey == other.tenantKey
    }

    override fun hashCode(): Int = id.hashCode()
}

const val MAX_ASSET_SIZE_BYTES: Long = 5 * 1024 * 1024 // 5MB

class AssetNotFoundException(tenantId: TenantKey, assetId: AssetKey) : RuntimeException("Asset $assetId not found for tenant $tenantId")

class AssetTooLargeException(sizeBytes: Long) : RuntimeException("Asset size $sizeBytes bytes exceeds maximum of $MAX_ASSET_SIZE_BYTES bytes (5MB)")

class UnsupportedAssetTypeException(mimeType: String, supported: Collection<String> = emptyList()) :
    RuntimeException(
        "Unsupported asset media type: $mimeType" +
            if (supported.isEmpty()) "" else ". Supported: ${supported.sorted()}",
    )

/**
 * Describes a template version that references an asset.
 */
data class AssetUsage(
    val templateName: String,
    val variantTitle: String?,
)

class AssetInUseException(
    val assetId: AssetKey,
    val usages: List<AssetUsage>,
) : RuntimeException(
    "Cannot delete asset $assetId: it is used in ${usages.joinToString { it.templateName + (it.variantTitle?.let { t -> " ($t)" } ?: "") }}",
)

package app.epistola.suite.assets.commands

import app.epistola.suite.assets.Asset
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.AssetTooLargeException
import app.epistola.suite.assets.MAX_ASSET_SIZE_BYTES
import app.epistola.suite.common.ids.AssetId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Command to upload a new asset.
 *
 * @property tenantId Tenant that owns the asset
 * @property name Human-readable name for the asset
 * @property mediaType The image media type
 * @property content Raw image bytes
 * @property width Image width in pixels (null for SVG)
 * @property height Image height in pixels (null for SVG)
 */
data class UploadAsset(
    val tenantId: TenantId,
    val name: String,
    val mediaType: AssetMediaType,
    val content: ByteArray,
    val width: Int?,
    val height: Int?,
) : Command<Asset> {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UploadAsset) return false
        return tenantId == other.tenantId && name == other.name
    }

    override fun hashCode(): Int = tenantId.hashCode() * 31 + name.hashCode()
}

@Component
class UploadAssetHandler(
    private val jdbi: Jdbi,
) : CommandHandler<UploadAsset, Asset> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: UploadAsset): Asset {
        require(command.name.isNotBlank()) { "Asset name must not be blank" }

        val sizeBytes = command.content.size.toLong()
        if (sizeBytes > MAX_ASSET_SIZE_BYTES) {
            throw AssetTooLargeException(sizeBytes)
        }

        val id = AssetId.generate()
        val now = OffsetDateTime.now()

        logger.info(
            "Uploading asset {} ({}, {} bytes) for tenant {}",
            command.name,
            command.mediaType.mimeType,
            sizeBytes,
            command.tenantId,
        )

        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO assets (id, tenant_id, name, media_type, size_bytes, width, height, content, created_at)
                VALUES (:id, :tenantId, :name, :mediaType, :sizeBytes, :width, :height, :content, :createdAt)
                """,
            )
                .bind("id", id.value)
                .bind("tenantId", command.tenantId)
                .bind("name", command.name)
                .bind("mediaType", command.mediaType.mimeType)
                .bind("sizeBytes", sizeBytes)
                .bind("width", command.width)
                .bind("height", command.height)
                .bind("content", command.content)
                .bind("createdAt", now)
                .execute()
        }

        return Asset(
            id = id,
            tenantId = command.tenantId,
            name = command.name,
            mediaType = command.mediaType,
            sizeBytes = sizeBytes,
            width = command.width,
            height = command.height,
            createdAt = now,
        )
    }
}

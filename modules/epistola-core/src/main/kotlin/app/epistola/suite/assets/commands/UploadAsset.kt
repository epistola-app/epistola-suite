package app.epistola.suite.assets.commands

import app.epistola.suite.assets.Asset
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.AssetTooLargeException
import app.epistola.suite.assets.MAX_ASSET_SIZE_BYTES
import app.epistola.suite.assets.assetContentScope
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.fonts.model.sha256Hex
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.storage.AssetContentStore
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Command to upload a new asset.
 *
 * @property tenantId Tenant that owns the asset
 * @property name Human-readable name for the asset
 * @property mediaType The image media type
 * @property content Raw image bytes
 * @property width Image width in pixels (null for SVG)
 * @property height Image height in pixels (null for SVG)
 * @property id Optional pre-defined asset ID (generated if null)
 */
data class UploadAsset(
    val tenantId: TenantKey,
    val name: String,
    val mediaType: AssetMediaType,
    val content: ByteArray,
    val width: Int?,
    val height: Int?,
    val catalogKey: CatalogKey,
    val id: AssetKey? = null,
    /** When true, the blob is stored in a per-tenant isolated dedup scope (see #738/#751). */
    val sensitive: Boolean = false,
) : Command<Asset>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_EDIT
    override val tenantKey get() = tenantId

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
    private val assetContentStore: AssetContentStore,
) : CommandHandler<UploadAsset, Asset> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: UploadAsset): Asset {
        requireCatalogEditable(command.tenantId, command.catalogKey)
        require(command.name.isNotBlank()) { "Asset name must not be blank" }

        val sizeBytes = command.content.size.toLong()
        if (sizeBytes > MAX_ASSET_SIZE_BYTES) {
            throw AssetTooLargeException(sizeBytes)
        }

        val id = command.id ?: AssetKey.generate()
        val now = EpistolaClock.offsetDateTime()
        val auditUser = currentUserIdOrNull()?.value

        logger.info(
            "Uploading asset {} ({}, {} bytes) for tenant {}",
            command.name,
            command.mediaType.mimeType,
            sizeBytes,
            command.tenantId,
        )

        // Content-addressable store: dedup identical bytes within the derived scope.
        // Store content first — an orphaned blob is harmless (the reaper reclaims it),
        // a DB row pointing to missing content is not.
        val contentHash = sha256Hex(command.content)
        // Non-sensitive assets dedup in the shared global scope; sensitive ones are
        // isolated per tenant (#738/#751).
        val scope = assetContentScope(command.sensitive, command.tenantId)
        assetContentStore.putIfAbsent(scope, contentHash, command.content, command.mediaType.mimeType, sizeBytes)

        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO assets (id, tenant_key, catalog_key, name, media_type, size_bytes, width, height, content_hash, sensitive, created_at, created_by)
                VALUES (:id, :tenantId, :catalogKey, :name, :mediaType, :sizeBytes, :width, :height, :contentHash, :sensitive, :createdAt, :createdBy)
                """,
            )
                .bind("id", id.value)
                .bind("tenantId", command.tenantId)
                .bind("catalogKey", command.catalogKey)
                .bind("name", command.name)
                .bind("mediaType", command.mediaType.mimeType)
                .bind("sizeBytes", sizeBytes)
                .bind("width", command.width)
                .bind("height", command.height)
                .bind("contentHash", contentHash)
                .bind("sensitive", command.sensitive)
                .bind("createdAt", now)
                .bind("createdBy", auditUser)
                .execute()
        }

        return Asset(
            id = id,
            tenantKey = command.tenantId,
            catalogKey = command.catalogKey,
            name = command.name,
            mediaType = command.mediaType,
            sizeBytes = sizeBytes,
            width = command.width,
            height = command.height,
            createdAt = now,
            createdBy = auditUser?.let { UserKey(it) },
        )
    }
}

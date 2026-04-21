package app.epistola.suite.catalog.commands

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.storage.ContentKey
import app.epistola.suite.storage.ContentStore
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream

/**
 * Imports an asset with a specific ID, preserving TemplateDocument image node references.
 * Skips if an asset with the same ID already exists (assets are immutable).
 */
data class ImportAsset(
    val tenantId: TenantId,
    val catalogKey: CatalogKey,
    val id: AssetKey,
    val name: String,
    val mediaType: AssetMediaType,
    val content: ByteArray,
    val width: Int? = null,
    val height: Int? = null,
) : Command<InstallStatus>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = tenantId.key

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImportAsset) return false
        return tenantId == other.tenantId && id == other.id
    }

    override fun hashCode(): Int = tenantId.hashCode() * 31 + id.hashCode()
}

@Component
class ImportAssetHandler(
    private val jdbi: Jdbi,
    private val contentStore: ContentStore,
) : CommandHandler<ImportAsset, InstallStatus> {

    override fun handle(command: ImportAsset): InstallStatus {
        val exists = jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery(
                """
                SELECT COUNT(*) > 0 FROM assets
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND id = :id
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("catalogKey", command.catalogKey)
                .bind("id", command.id.value)
                .mapTo(Boolean::class.java)
                .one()
        }

        if (exists) {
            // Update existing asset metadata and replace content
            jdbi.useHandle<Exception> { handle ->
                handle.createUpdate(
                    """
                    UPDATE assets
                    SET name = :name, media_type = :mediaType, width = :width, height = :height, size_bytes = :sizeBytes
                    WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND id = :id
                    """,
                )
                    .bind("tenantKey", command.tenantKey)
                    .bind("catalogKey", command.catalogKey)
                    .bind("id", command.id.value)
                    .bind("name", command.name)
                    .bind("mediaType", command.mediaType.mimeType)
                    .bind("width", command.width)
                    .bind("height", command.height)
                    .bind("sizeBytes", command.content.size.toLong())
                    .execute()
            }

            val key = ContentKey.asset(command.tenantKey, command.id)
            contentStore.put(key, ByteArrayInputStream(command.content), command.mediaType.mimeType, command.content.size.toLong())

            return InstallStatus.UPDATED
        }

        // Upload with the original ID to preserve template image node references
        UploadAsset(
            tenantId = command.tenantKey,
            catalogKey = command.catalogKey,
            name = command.name,
            mediaType = command.mediaType,
            content = command.content,
            width = command.width,
            height = command.height,
            id = command.id,
        ).execute()

        return InstallStatus.INSTALLED
    }
}

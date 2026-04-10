package app.epistola.suite.catalog.commands

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Imports an asset with a specific ID, preserving TemplateDocument image node references.
 * Skips if an asset with the same ID already exists (assets are immutable).
 */
data class ImportAsset(
    val tenantId: TenantId,
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
) : CommandHandler<ImportAsset, InstallStatus> {

    override fun handle(command: ImportAsset): InstallStatus {
        // Check if asset with this ID already exists — assets are immutable
        val exists = jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery(
                """
                SELECT COUNT(*) > 0 FROM assets
                WHERE tenant_key = :tenantKey AND id = :id
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("id", command.id.value)
                .mapTo(Boolean::class.java)
                .one()
        }

        if (exists) return InstallStatus.SKIPPED

        // Upload with the original ID to preserve template image node references
        UploadAsset(
            tenantId = command.tenantKey,
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

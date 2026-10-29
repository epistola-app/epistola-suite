// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets.commands

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.ImageFields
import app.epistola.suite.assets.ImageStore
import app.epistola.suite.assets.SvgContentPolicy
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.validation.FieldLimits.MAX_NAME_COLUMN_LENGTH
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Imports an asset with a specific ID, preserving TemplateDocument image node references.
 * Skips if an asset with the same ID already exists (assets are immutable).
 */
data class ImportAsset(
    val catalog: CatalogId,
    val id: AssetKey,
    val name: String,
    val mediaType: AssetMediaType,
    val content: ByteArray,
    val width: Int? = null,
    val height: Int? = null,
    /** Producer metadata (contract `CatalogResource.metadata`), as received; null keeps what the draft holds. */
    val metadata: app.epistola.suite.catalog.revisions.ProducerMetadata? = null,
) : Command<InstallStatus>,
    RequiresPermission {
    val tenantId: TenantId get() = catalog.tenantId
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = tenantId.key

    init {
        // Column ceiling (#692): an image's name is at most 255 characters. Covers the re-import
        // path; the insert path additionally validates via UploadAsset.
        validate("name", name.length <= MAX_NAME_COLUMN_LENGTH) { "Name must be $MAX_NAME_COLUMN_LENGTH characters or less" }
        // The re-import UPDATE path never reaches UploadAsset, so the SVG policy is checked here too.
        SvgContentPolicy.requireSafe(mediaType, content)
    }

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
    private val images: ImageStore,
) : CommandHandler<ImportAsset, InstallStatus> {

    override fun handle(command: ImportAsset): InstallStatus {
        val exists = jdbi.withHandle<Boolean, Exception> { images.get(it, command.catalog, command.id.value) != null }
        if (!exists) {
            // Upload with the original key, so the image nodes naming it keep resolving.
            UploadAsset(
                catalog = command.catalog,
                name = command.name,
                mediaType = command.mediaType,
                content = command.content,
                width = command.width,
                height = command.height,
                id = command.id,
            ).execute()
            // An upload says nothing of a producer's metadata; what the catalog gave goes on it.
            if (!command.metadata.isNullOrEmpty()) write(command)
            return InstallStatus.INSTALLED
        }
        // Re-import replaces the content; the previous bytes go when nothing names them any more.
        write(command)
        return InstallStatus.UPDATED
    }

    private fun write(command: ImportAsset) {
        val contentHash = images.storeBytes(command.content, command.mediaType)
        jdbi.inTransaction<Unit, Exception> { handle ->
            images.put(handle, command.catalog, command.id.value, ImageFields(command.name, command.mediaType, command.width, command.height, contentHash, command.metadata), currentUserIdOrNull()?.value)
        }
    }
}

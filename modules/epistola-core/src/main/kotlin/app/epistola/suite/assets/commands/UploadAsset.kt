// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets.commands

import app.epistola.suite.assets.Asset
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.AssetTooLargeException
import app.epistola.suite.assets.ImageFields
import app.epistola.suite.assets.ImagePixelPolicy
import app.epistola.suite.assets.ImageStore
import app.epistola.suite.assets.MAX_ASSET_SIZE_BYTES
import app.epistola.suite.assets.SvgContentPolicy
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.validation.FieldLimits.MAX_NAME_COLUMN_LENGTH
import app.epistola.suite.validation.validate
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
    val catalog: CatalogId,
    val name: String,
    val mediaType: AssetMediaType,
    val content: ByteArray,
    val width: Int?,
    val height: Int?,
    val id: AssetKey? = null,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Command<Asset>,
    RequiresPermission {
    val tenantId: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.TEMPLATE_EDIT
    override val tenantKey get() = tenantId

    init {
        // Column ceiling (#692): assets.name is VARCHAR(255). The blank check stays in
        // the handler; this bounds length so an over-long name never overflows the column.
        validate("name", name.length <= MAX_NAME_COLUMN_LENGTH) { "Name must be $MAX_NAME_COLUMN_LENGTH characters or less" }
        // Load-bearing for the stored-XSS fix (#644) — don't relax without reading it.
        validate("name", name.none { it == '<' || it == '>' || it.isISOControl() }) {
            "Name must not contain '<', '>', or control characters"
        }
        // An SVG must be self-contained and inert before it is stored (see SvgContentPolicy).
        SvgContentPolicy.requireSafe(mediaType, content)
        // A raster image must not declare more pixels than a render can decode.
        ImagePixelPolicy.requireReasonable(mediaType, content)
    }

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
    private val images: ImageStore,
) : CommandHandler<UploadAsset, Asset> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: UploadAsset): Asset {
        requireCatalogEditable(command.catalog)
        require(command.name.isNotBlank()) { "Asset name must not be blank" }

        val sizeBytes = command.content.size.toLong()
        if (sizeBytes > MAX_ASSET_SIZE_BYTES) {
            throw AssetTooLargeException(sizeBytes)
        }
        val id = command.id ?: AssetKey.generate()
        logger.info("Uploading image {} ({}, {} bytes) for tenant {}", command.name, command.mediaType.mimeType, sizeBytes, command.tenantId)

        // Bytes first, once per installation: a blob nothing names yet is harmless (the reaper takes
        // it), a content naming bytes that are not there is not.
        val contentHash = images.storeBytes(command.content, command.mediaType)
        return jdbi.inTransaction<Asset, Exception> { handle ->
            images.create(handle, command.catalog, id.value, ImageFields(command.name, command.mediaType, command.width, command.height, contentHash), currentUserIdOrNull()?.value)
        }
    }
}

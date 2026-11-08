// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.commands

import app.epistola.suite.common.ids.SupportRequestAssetId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.SelfManagedTransaction
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.support.requests.SupportRequestAsset
import app.epistola.suite.support.requests.consent.lockedConsentInForceId
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component
import java.util.Base64

data class AddSupportRequestAsset(
    val id: SupportRequestAssetId,
    val content: ByteArray,
    val contentType: String,
    val filename: String?,
) : Command<SupportRequestAsset>,
    RequiresPermission,
    // Its IMMEDIATE handler sends the asset to the support service: no open transaction.
    SelfManagedTransaction {
    override val permission get() = Permission.SUPPORT_REQUEST_SUBMIT
    override val tenantKey get() = id.tenantKey

    init {
        require(content.isNotEmpty()) { "Asset content must not be empty" }
        require(contentType.isNotBlank()) { "Content type is required" }
    }

    companion object {
        /**
         * Maximum stored asset size. The client downscales screenshots under this cap; this is
         * a server-side safety net, well under the support service's 2 MiB limit per asset.
         * Oversized data URLs are dropped (returns null).
         */
        const val MAX_ASSET_BYTES: Int = 1024 * 1024 // 1 MB

        /** The screenshot types taken and served. */
        val IMAGE_TYPES: Set<String> = setOf("image/png", "image/jpeg", "image/webp")

        /**
         * Creates an [AddSupportRequestAsset] from a base64 data URL (e.g., `data:image/png;base64,iVBOR...`).
         *
         * @return the command, or null if the data URL is malformed or exceeds [MAX_ASSET_BYTES]
         */

        fun fromDataUrl(id: SupportRequestAssetId, dataUrl: String): AddSupportRequestAsset? {
            if (!dataUrl.startsWith("data:")) return null
            val parts = dataUrl.split(",", limit = 2)
            if (parts.size != 2) return null

            // Only the image types the support service takes (docs/support-protocol.md): nothing a browser
            // would run when the screenshot is opened from this installation's origin.
            val contentType = parts[0].removePrefix("data:").removeSuffix(";base64").takeIf { it in IMAGE_TYPES } ?: return null
            val bytes = try {
                Base64.getDecoder().decode(parts[1])
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (bytes.isEmpty() || bytes.size > MAX_ASSET_BYTES) return null

            val extension = contentType.substringAfter("image/", "png")
            return AddSupportRequestAsset(
                id = id,
                content = bytes,
                contentType = contentType,
                filename = "screenshot.$extension",
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AddSupportRequestAsset) return false
        return id == other.id &&
            content.contentEquals(other.content) &&
            contentType == other.contentType &&
            filename == other.filename
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + content.contentHashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + (filename?.hashCode() ?: 0)
        return result
    }
}

@Component
class AddSupportRequestAssetHandler(
    private val jdbi: Jdbi,
) : CommandHandler<AddSupportRequestAsset, SupportRequestAsset> {
    override fun handle(command: AddSupportRequestAsset): SupportRequestAsset = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            """
            INSERT INTO support_request_assets (
                tenant_key, request_id, id, content, content_type, filename, created_at, sync_consent_id
            )
            VALUES (
                :tenantKey, :feedbackId, :id, :content, :contentType, :filename, NOW(), ${lockedConsentInForceId(":tenantKey")}
            )
            RETURNING tenant_key, request_id, id, content_type, filename, created_at
            """,
        )
            .bind("tenantKey", command.id.tenantKey)
            .bind("feedbackId", command.id.supportRequestKey.value)
            .bind("id", command.id.key.value)
            .bind("content", command.content)
            .bind("contentType", command.contentType)
            .bind("filename", command.filename)
            .mapTo(SupportRequestAsset::class.java)
            .one()
    }
}

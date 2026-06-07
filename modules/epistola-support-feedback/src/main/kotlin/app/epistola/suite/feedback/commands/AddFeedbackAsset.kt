package app.epistola.suite.feedback.commands

import app.epistola.suite.common.ids.FeedbackAssetId
import app.epistola.suite.feedback.FeedbackAsset
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component
import java.util.Base64

data class AddFeedbackAsset(
    val id: FeedbackAssetId,
    val content: ByteArray,
    val contentType: String,
    val filename: String?,
) : Command<FeedbackAsset>,
    RequiresPermission {
    override val permission get() = Permission.DOCUMENT_VIEW
    override val tenantKey get() = id.tenantKey

    init {
        require(content.isNotEmpty()) { "Asset content must not be empty" }
        require(contentType.isNotBlank()) { "Content type is required" }
    }

    companion object {
        /**
         * Creates an [AddFeedbackAsset] from a base64 data URL (e.g., `data:image/png;base64,iVBOR...`).
         *
         * @return the command, or null if the data URL is malformed
         */
        fun fromDataUrl(id: FeedbackAssetId, dataUrl: String): AddFeedbackAsset? {
            if (!dataUrl.startsWith("data:")) return null
            val parts = dataUrl.split(",", limit = 2)
            if (parts.size != 2) return null

            val contentType = parts[0].removePrefix("data:").removeSuffix(";base64")
            val bytes = try {
                Base64.getDecoder().decode(parts[1])
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (bytes.isEmpty()) return null

            val extension = contentType.substringAfter("image/", "png")
            return AddFeedbackAsset(
                id = id,
                content = bytes,
                contentType = contentType,
                filename = "screenshot.$extension",
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AddFeedbackAsset) return false
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
class AddFeedbackAssetHandler(
    private val jdbi: Jdbi,
) : CommandHandler<AddFeedbackAsset, FeedbackAsset> {
    override fun handle(command: AddFeedbackAsset): FeedbackAsset = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            """
            INSERT INTO feedback_assets (
                tenant_key, feedback_id, id, content, content_type, filename, created_at
            )
            VALUES (
                :tenantKey, :feedbackId, :id, :content, :contentType, :filename, NOW()
            )
            RETURNING tenant_key, feedback_id, id, content_type, filename, created_at
            """,
        )
            .bind("tenantKey", command.id.tenantKey)
            .bind("feedbackId", command.id.feedbackKey.value)
            .bind("id", command.id.key.value)
            .bind("content", command.content)
            .bind("contentType", command.contentType)
            .bind("filename", command.filename)
            .mapTo(FeedbackAsset::class.java)
            .one()
    }
}

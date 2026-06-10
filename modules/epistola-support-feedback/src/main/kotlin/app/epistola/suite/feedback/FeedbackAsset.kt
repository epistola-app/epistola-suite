package app.epistola.suite.feedback

import app.epistola.suite.common.ids.FeedbackAssetKey
import app.epistola.suite.common.ids.FeedbackKey
import app.epistola.suite.common.ids.TenantKey
import java.time.OffsetDateTime

data class FeedbackAsset(
    val id: FeedbackAssetKey,
    val feedbackId: FeedbackKey,
    val tenantKey: TenantKey,
    val contentType: String,
    val filename: String?,
    val createdAt: OffsetDateTime,
)

data class FeedbackAssetContent(
    val content: ByteArray,
    val contentType: String,
    val filename: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FeedbackAssetContent) return false
        return content.contentEquals(other.content) && contentType == other.contentType && filename == other.filename
    }

    override fun hashCode(): Int {
        var result = content.contentHashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + (filename?.hashCode() ?: 0)
        return result
    }
}

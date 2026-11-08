// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests

import app.epistola.suite.common.ids.SupportRequestAssetKey
import app.epistola.suite.common.ids.SupportRequestKey
import app.epistola.suite.common.ids.TenantKey
import java.time.OffsetDateTime

data class SupportRequestAsset(
    val id: SupportRequestAssetKey,
    val requestId: SupportRequestKey,
    val tenantKey: TenantKey,
    val contentType: String,
    val filename: String?,
    val createdAt: OffsetDateTime,
)

data class SupportRequestAssetContent(
    val content: ByteArray,
    val contentType: String,
    val filename: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SupportRequestAssetContent) return false
        return content.contentEquals(other.content) && contentType == other.contentType && filename == other.filename
    }

    override fun hashCode(): Int {
        var result = content.contentHashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + (filename?.hashCode() ?: 0)
        return result
    }
}

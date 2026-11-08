// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests

import app.epistola.suite.common.ids.SupportRequestCommentKey
import app.epistola.suite.common.ids.SupportRequestKey
import app.epistola.suite.common.ids.TenantKey
import java.time.OffsetDateTime

data class SupportRequestComment(
    val id: SupportRequestCommentKey,
    val requestId: SupportRequestKey,
    val tenantKey: TenantKey,
    val body: String,
    val authorName: String,
    val authorEmail: String?,
    val source: CommentSource,
    val externalCommentId: String?,
    val createdAt: OffsetDateTime,
    /** Refusals so far when sending it to Epistola support; read only by the retry sweep. */
    val syncAttempts: Int = 0,
    /** Who wrote it here; null for a reply from a connection, or once that user is deleted. */
    val authorId: java.util.UUID? = null,
)

enum class CommentSource {
    LOCAL,
    EXTERNAL,
}

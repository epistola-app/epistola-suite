// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests

import app.epistola.suite.common.ids.SupportRequestKey
import app.epistola.suite.common.ids.TenantKey
import java.net.URI
import java.time.OffsetDateTime

data class SupportRequestSummary(
    val id: SupportRequestKey,
    val tenantKey: TenantKey,
    val title: String,
    val category: SupportRequestCategory,
    val status: SupportRequestStatus,
    val priority: SupportRequestPriority,
    val sourceUrl: String?,
    val createdByName: String,
    val commentCount: Int,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    val sourcePath: String?
        get() = sourceUrl?.let { runCatching { URI.create(it).path }.getOrNull() }
}

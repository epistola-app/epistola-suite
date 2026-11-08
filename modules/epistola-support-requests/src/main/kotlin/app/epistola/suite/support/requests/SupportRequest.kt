// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests

import app.epistola.suite.common.ids.SupportRequestKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import java.time.OffsetDateTime

data class SupportRequest(
    val id: SupportRequestKey,
    val tenantKey: TenantKey,
    val title: String,
    val description: String,
    val category: SupportRequestCategory,
    val status: SupportRequestStatus,
    /** The priority the handler works with; it starts as the [requestedPriority]. */
    val priority: SupportRequestPriority,
    val sourceUrl: String?,
    val consoleLogs: String?,
    val metadata: String?,
    val createdBy: UserKey,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val externalRef: String?,
    val externalUrl: String?,
    val syncStatus: SyncStatus,
    val syncAttempts: Int = 0,
    /** The server's log lines of the submitter's recent requests (a JSON array), when attached. */
    val serverLogs: String? = null,
    /** The priority the submitter asked for; null only on rows read without it. */
    val requestedPriority: SupportRequestPriority? = null,
    /** Who handles it: null, the installation's own inbox; otherwise the support connection it went to. */
    val handlerConnection: java.util.UUID? = null,
) {
    /** The installation's own inbox handles it: the desk sets its status and final priority. */
    val handledHere: Boolean get() = handlerConnection == null
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.sync

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.support.requests.SupportRequestPriority
import app.epistola.suite.support.requests.SupportRequestStatus
import java.time.Instant

/**
 * Represents an update fetched from the external sync target via polling.
 *
 * Carries [tenantKey] because a single installation-wide poll returns updates across all
 * tenants; the poll scheduler uses it to resolve the local feedback item.
 */
sealed class ExternalUpdate {
    /** Monotonic feed cursor; the poll scheduler persists the highest processed [seq]. */
    abstract val seq: Long
    abstract val tenantKey: TenantKey
    abstract val externalRef: String
    abstract val occurredAt: Instant

    data class Comment(
        override val seq: Long,
        override val tenantKey: TenantKey,
        override val externalRef: String,
        override val occurredAt: Instant,
        val externalCommentId: String,
        val authorName: String,
        val authorEmail: String?,
        val body: String,
    ) : ExternalUpdate()

    data class StatusChange(
        override val seq: Long,
        override val tenantKey: TenantKey,
        override val externalRef: String,
        override val occurredAt: Instant,
        val newStatus: SupportRequestStatus,
    ) : ExternalUpdate()

    /** The handler set the priority it works with; the requested priority stays as the submitter set it. */
    data class PriorityChange(
        override val seq: Long,
        override val tenantKey: TenantKey,
        override val externalRef: String,
        override val occurredAt: Instant,
        val newPriority: SupportRequestPriority,
    ) : ExternalUpdate()
}

/**
 * One page of the external update feed after what this installation acknowledged: the updates it
 * understands, the sequence of the page's last change ([lastSequence], also when that change is of a
 * type this suite skips; null on an empty page), to acknowledge once handled, and whether more pages
 * remain ([hasMore]).
 */
data class ExternalUpdatePage(
    val updates: List<ExternalUpdate>,
    val lastSequence: Long?,
    val hasMore: Boolean,
)

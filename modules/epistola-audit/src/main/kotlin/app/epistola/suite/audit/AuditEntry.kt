package app.epistola.suite.audit

import app.epistola.suite.common.ids.TenantKey
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Read model for a single `audit_log` row, as shown in the Audit viewer.
 *
 * The stored row is PII-free: it holds only [actorUserId] (an opaque surrogate).
 * [actorDisplayName] is resolved at read time via a LEFT JOIN to `users` purely
 * for display in the permission-gated viewer; it is `null` when the user row no
 * longer exists (e.g. after a GDPR erasure), in which case the UI renders
 * "(deleted user)". `tenantKey` is null for system/root commands.
 */
data class AuditEntry(
    val id: UUID,
    val occurredAt: OffsetDateTime,
    val tenantKey: TenantKey?,
    val actorUserId: UUID?,
    val actorDisplayName: String?,
    val action: String,
    val operation: String,
    val entityType: String?,
    val entityId: String?,
    val outcome: String,
    val errorCode: String?,
    val details: Map<String, String>,
    val instanceId: String?,
) {
    /** True for system/root rows that carry no tenant. */
    val isSystem: Boolean get() = tenantKey == null

    /** True when the command failed. */
    val isFailure: Boolean get() = outcome == "FAILURE"

    /** True for a data-access (query) row, as opposed to a WRITE (command). */
    val isRead: Boolean get() = operation == "READ"

    /** Display label for the actor; falls back when the user row is gone or unauthenticated. */
    val actorLabel: String
        get() = when {
            actorDisplayName != null -> actorDisplayName
            actorUserId != null -> "(deleted user)"
            else -> "(unauthenticated)"
        }
}

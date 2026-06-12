package app.epistola.suite.logs

import app.epistola.suite.common.ids.TenantKey
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Read model for a single persisted application log row, as shown in the Logs
 * viewer. `tenantKey` is null for system/background events.
 */
data class ApplicationLogEntry(
    val id: UUID,
    val occurredAt: OffsetDateTime,
    val level: String,
    val logger: String,
    val message: String,
    val thread: String?,
    val instanceId: String,
    val tenantKey: TenantKey?,
    val traceId: String?,
    val spanId: String?,
    val exception: String?,
) {
    /** True for system/background rows that carry no tenant. */
    val isSystem: Boolean get() = tenantKey == null

    /** The logger's simple class name (after the last dot); the full name on hover in the UI. */
    val shortLogger: String get() = logger.substringAfterLast('.')
}

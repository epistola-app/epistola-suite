package app.epistola.suite.environments

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Tenant environment for version activation (e.g., staging, production).
 */
data class Environment(
    val id: UUID,
    val tenantId: UUID,
    val name: String,
    val createdAt: OffsetDateTime,
)

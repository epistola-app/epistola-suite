package app.epistola.suite.environments

import java.time.OffsetDateTime

/**
 * Tenant environment for version activation (e.g., staging, production).
 */
data class Environment(
    val id: Long,
    val tenantId: Long,
    val name: String,
    val createdAt: OffsetDateTime,
)

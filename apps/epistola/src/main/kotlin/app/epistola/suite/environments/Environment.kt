package app.epistola.suite.environments

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TenantId
import java.time.OffsetDateTime

/**
 * Tenant environment for version activation (e.g., staging, production).
 */
data class Environment(
    val id: EnvironmentId,
    val tenantId: TenantId,
    val name: String,
    val createdAt: OffsetDateTime,
)

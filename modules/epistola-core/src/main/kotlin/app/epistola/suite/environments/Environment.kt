package app.epistola.suite.environments

import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantKey
import java.time.OffsetDateTime

/**
 * Tenant environment for version activation (e.g., staging, production).
 */
data class Environment(
    val id: EnvironmentKey,
    val tenantId: TenantKey,
    val name: String,
    val createdAt: OffsetDateTime,
)

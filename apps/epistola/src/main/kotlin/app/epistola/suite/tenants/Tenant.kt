package app.epistola.suite.tenants

import java.time.OffsetDateTime

data class Tenant(
    val id: Long,
    val name: String,
    val createdAt: OffsetDateTime,
)

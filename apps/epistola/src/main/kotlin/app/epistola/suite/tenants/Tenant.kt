package app.epistola.suite.tenants

import java.time.OffsetDateTime
import java.util.UUID

data class Tenant(
    val id: UUID,
    val name: String,
    val createdAt: OffsetDateTime,
)

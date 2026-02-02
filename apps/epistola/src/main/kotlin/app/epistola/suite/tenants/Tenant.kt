package app.epistola.suite.tenants

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import java.time.OffsetDateTime

data class Tenant(
    val id: TenantId,
    val name: String,
    val defaultThemeId: ThemeId?,
    val createdAt: OffsetDateTime,
)

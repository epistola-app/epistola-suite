package app.epistola.suite.tenants

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import java.time.OffsetDateTime

data class Tenant(
    val id: TenantKey,
    val name: String,
    val defaultThemeId: ThemeKey?,
    val createdAt: OffsetDateTime,
)

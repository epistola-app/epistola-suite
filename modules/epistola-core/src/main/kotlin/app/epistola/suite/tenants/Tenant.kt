package app.epistola.suite.tenants

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import java.time.OffsetDateTime

data class Tenant(
    val id: TenantKey,
    val name: String,
    val defaultThemeCatalogKey: CatalogKey? = CatalogKey.DEFAULT,
    val defaultThemeKey: ThemeKey?,
    val createdAt: OffsetDateTime,
)

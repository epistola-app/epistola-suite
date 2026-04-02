package app.epistola.suite.catalog

import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey

data class CatalogTemplate(
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val templateKey: TemplateKey,
    val catalogResourceSlug: String,
)

package app.epistola.suite.fonts

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantKey

class FontNotFoundException(
    val tenantId: TenantKey,
    val catalogId: CatalogKey,
    val fontId: FontKey,
) : RuntimeException("Font ${fontId.value} not found in catalog ${catalogId.value} for tenant ${tenantId.value}")

package app.epistola.suite.attributes

import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey

class AttributeNotFoundException(
    val tenantId: TenantKey,
    val catalogId: CatalogKey,
    val attributeId: AttributeKey,
) : RuntimeException("Attribute ${attributeId.value} not found in catalog ${catalogId.value} for tenant ${tenantId.value}")

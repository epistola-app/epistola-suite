package app.epistola.suite.tenants

import app.epistola.suite.common.ids.TenantKey

class TenantNotFoundException(
    val tenantId: TenantKey,
) : RuntimeException("Tenant not found: ${tenantId.value}")

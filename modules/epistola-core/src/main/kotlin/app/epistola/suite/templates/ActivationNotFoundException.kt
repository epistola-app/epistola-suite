package app.epistola.suite.templates

import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey

class ActivationNotFoundException(
    val tenantId: TenantKey,
    val variantId: VariantKey,
    val environmentId: EnvironmentKey,
) : RuntimeException("No activation found for variant $variantId in environment $environmentId for tenant $tenantId")

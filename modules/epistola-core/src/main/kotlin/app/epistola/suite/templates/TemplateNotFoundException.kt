package app.epistola.suite.templates

import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey

class TemplateNotFoundException(
    val tenantId: TenantKey,
    val templateId: TemplateKey,
) : RuntimeException("Template ${templateId.value} not found for tenant ${tenantId.value}")

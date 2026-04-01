package app.epistola.suite.documents

import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey

/**
 * Thrown when a template/variant combination does not exist for a tenant.
 */
class TemplateVariantNotFoundException(
    val tenantId: TenantKey,
    val templateId: TemplateKey,
    val variantId: VariantKey,
) : RuntimeException("Template $templateId variant $variantId not found for tenant $tenantId")

/**
 * Thrown when a template version does not exist.
 */
class VersionNotFoundException(
    val tenantId: TenantKey,
    val templateId: TemplateKey,
    val variantId: VariantKey,
    val versionId: VersionKey,
) : RuntimeException("Version $versionId not found for template $templateId variant $variantId")

/**
 * Thrown when an environment does not exist for a tenant.
 */
class EnvironmentNotFoundException(
    val tenantId: TenantKey,
    val environmentId: EnvironmentKey,
) : RuntimeException("Environment $environmentId not found for tenant $tenantId")

/**
 * Thrown when no default variant exists for a template.
 */
class DefaultVariantNotFoundException(
    val tenantId: TenantKey,
    val templateId: TemplateKey,
) : RuntimeException("No default variant found for template $templateId in tenant $tenantId")

package app.epistola.suite.templates.model

import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import java.time.OffsetDateTime

/**
 * Environment activation mapping a variant to its active version in an environment.
 */
data class EnvironmentActivation(
    val tenantKey: TenantKey,
    val environmentKey: EnvironmentKey,
    val templateKey: TemplateKey,
    val variantKey: VariantKey,
    val versionKey: VersionKey,
    val activatedAt: OffsetDateTime,
)

/**
 * Enriched activation with environment and version details.
 * versionKey.value IS the version number (1-200).
 */
data class ActivationDetails(
    val environmentKey: EnvironmentKey,
    val environmentName: String,
    val versionKey: VersionKey,
    val activatedAt: OffsetDateTime,
)

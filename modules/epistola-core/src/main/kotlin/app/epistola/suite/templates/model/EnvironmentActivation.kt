package app.epistola.suite.templates.model

import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import java.time.OffsetDateTime

/**
 * Environment activation mapping a variant to its active version in an environment.
 */
data class EnvironmentActivation(
    val tenantId: TenantKey,
    val environmentId: EnvironmentKey,
    val variantId: VariantKey,
    val versionId: VersionKey,
    val activatedAt: OffsetDateTime,
)

/**
 * Enriched activation with environment and version details.
 * versionId.value IS the version number (1-200).
 */
data class ActivationDetails(
    val environmentId: EnvironmentKey,
    val environmentName: String,
    val versionId: VersionKey,
    val activatedAt: OffsetDateTime,
)

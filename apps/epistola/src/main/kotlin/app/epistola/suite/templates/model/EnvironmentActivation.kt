package app.epistola.suite.templates.model

import java.time.OffsetDateTime

/**
 * Environment activation mapping a variant to its active version in an environment.
 */
data class EnvironmentActivation(
    val environmentId: Long,
    val variantId: Long,
    val versionId: Long,
    val activatedAt: OffsetDateTime,
)

/**
 * Enriched activation with environment and version details.
 */
data class ActivationDetails(
    val environmentId: Long,
    val environmentName: String,
    val versionId: Long,
    val versionNumber: Int,
    val activatedAt: OffsetDateTime,
)

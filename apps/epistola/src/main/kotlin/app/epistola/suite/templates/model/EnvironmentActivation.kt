package app.epistola.suite.templates.model

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Environment activation mapping a variant to its active version in an environment.
 */
data class EnvironmentActivation(
    val environmentId: UUID,
    val variantId: UUID,
    val versionId: UUID,
    val activatedAt: OffsetDateTime,
)

/**
 * Enriched activation with environment and version details.
 */
data class ActivationDetails(
    val environmentId: UUID,
    val environmentName: String,
    val versionId: UUID,
    val versionNumber: Int,
    val activatedAt: OffsetDateTime,
)

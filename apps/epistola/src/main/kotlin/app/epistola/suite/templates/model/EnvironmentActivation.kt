package app.epistola.suite.templates.model

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import java.time.OffsetDateTime

/**
 * Environment activation mapping a variant to its active version in an environment.
 */
data class EnvironmentActivation(
    val environmentId: EnvironmentId,
    val variantId: VariantId,
    val versionId: VersionId,
    val activatedAt: OffsetDateTime,
)

/**
 * Enriched activation with environment and version details.
 * versionId.value IS the version number (1-200).
 */
data class ActivationDetails(
    val environmentId: EnvironmentId,
    val environmentName: String,
    val versionId: VersionId,
    val activatedAt: OffsetDateTime,
)

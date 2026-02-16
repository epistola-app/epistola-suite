package app.epistola.suite.templates.commands.versions

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId

/**
 * Thrown when attempting to archive a version that is still active in one or more environments.
 * The version must be removed from all environments before it can be archived.
 */
class VersionStillActiveException(
    val versionId: VersionId,
    val variantId: VariantId,
    val activeEnvironments: List<EnvironmentId>,
) : RuntimeException(
    "Cannot archive version '${versionId.value}' of variant '${variantId.value}': " +
        "still active in environments ${activeEnvironments.joinToString { it.value }}",
)

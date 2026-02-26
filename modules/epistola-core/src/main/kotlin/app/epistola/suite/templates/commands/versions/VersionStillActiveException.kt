package app.epistola.suite.templates.commands.versions

import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey

/**
 * Thrown when attempting to archive a version that is still active in one or more environments.
 * The version must be removed from all environments before it can be archived.
 */
class VersionStillActiveException(
    val versionId: VersionKey,
    val variantId: VariantKey,
    val activeEnvironments: List<EnvironmentKey>,
) : RuntimeException(
    "Cannot archive version '${versionId.value}' of variant '${variantId.value}': " +
        "still active in environments ${activeEnvironments.joinToString { it.value }}",
)

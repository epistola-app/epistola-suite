package app.epistola.suite.environments

import app.epistola.suite.common.ids.EnvironmentKey

class EnvironmentInUseException(
    val environmentId: EnvironmentKey,
    val activationCount: Long,
) : RuntimeException(
    "Cannot delete environment $environmentId: it has $activationCount active template version(s)",
)

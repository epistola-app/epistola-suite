package app.epistola.suite.environments

import app.epistola.suite.common.ids.EnvironmentId

class EnvironmentInUseException(
    val environmentId: EnvironmentId,
    val activationCount: Long,
) : RuntimeException(
    "Cannot delete environment $environmentId: it has $activationCount active template version(s)",
)

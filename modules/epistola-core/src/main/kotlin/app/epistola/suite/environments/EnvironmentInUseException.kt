// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.common.ids.EnvironmentKey

class EnvironmentInUseException(
    val environmentId: EnvironmentKey,
    val activationCount: Long,
) : RuntimeException(
    "Cannot delete environment $environmentId: it has $activationCount active template version(s)",
)

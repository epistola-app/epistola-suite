// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.UserKey
import java.time.OffsetDateTime

/** The catalog release an environment serves for one catalog. */
data class CatalogDeployment(
    val environmentKey: EnvironmentKey,
    val catalogKey: CatalogKey,
    val version: String,
    val deployedAt: OffsetDateTime,
    val deployedBy: UserKey?,
)

/** A release cannot be deployed: it does not exist, or it kept no content to render from. */
class ReleaseNotDeployableException(
    val catalogKey: CatalogKey,
    val version: String,
    reason: String,
) : RuntimeException("Release ${catalogKey.value}@$version cannot be deployed: $reason")

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
    /** Generation requests through the environment accepted for this version since it was deployed. */
    val documentsRequested: Long = 0,
    /** When the last of those was accepted; null when none was. */
    val lastUsedAt: OffsetDateTime? = null,
)

/** A release cannot be deployed: it does not exist. */
class ReleaseNotDeployableException(
    val catalogKey: CatalogKey,
    val version: String,
    reason: String,
) : RuntimeException("Release ${catalogKey.value}@$version cannot be deployed: $reason")

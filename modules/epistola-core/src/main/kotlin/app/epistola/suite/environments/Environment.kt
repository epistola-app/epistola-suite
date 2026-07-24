// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import java.time.OffsetDateTime

/**
 * Tenant environment for version activation (e.g., staging, production).
 */
data class Environment(
    val id: EnvironmentKey,
    val tenantKey: TenantKey,
    val name: String,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val createdBy: UserKey? = null,
    val updatedBy: UserKey? = null,
)

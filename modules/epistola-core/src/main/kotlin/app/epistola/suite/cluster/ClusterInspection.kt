// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.cluster

import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.RequiresPlatformRole

/**
 * A read of the installation's cluster: its nodes, scheduled tasks and timers. They span every
 * tenant (timer keys name tenants and support connections), so no tenant role reaches them; the
 * installation administrator and the platform observer do, as the platform cluster page does.
 */
interface ClusterInspection : RequiresPlatformRole {
    override val platformRole: PlatformRole get() = PlatformRole.INSTALLATION_ADMINISTRATOR
    override val alsoAllowedPlatformRoles: Set<PlatformRole> get() = setOf(PlatformRole.PLATFORM_OBSERVER)
}

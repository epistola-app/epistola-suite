// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.snapshots

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.SystemUser

/**
 * System principal for background snapshot work — the backup scheduler, the upgrading
 * freshness sweep, and restore. These run on scheduler threads outside any HTTP request, so they
 * have no [app.epistola.suite.security.SecurityContext]; this grants all roles for the one tenant
 * being processed so the permission-gated snapshot build / catalog import commands authorize.
 */
fun snapshotSystemPrincipal(tenantKey: TenantKey): EpistolaPrincipal = SystemUser.principalForTenant(tenantKey)

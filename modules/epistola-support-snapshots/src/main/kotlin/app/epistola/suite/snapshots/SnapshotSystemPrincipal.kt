package app.epistola.suite.snapshots

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.SystemUser
import app.epistola.suite.security.TenantRole

/**
 * System principal for background snapshot work — the backup scheduler, the upgrading
 * freshness sweep, and restore. These run on scheduler threads outside any HTTP request, so they
 * have no [app.epistola.suite.security.SecurityContext]; this grants all roles for the one tenant
 * being processed so the permission-gated snapshot build / catalog import commands authorize.
 */
fun snapshotSystemPrincipal(tenantKey: TenantKey): EpistolaPrincipal = EpistolaPrincipal(
    userId = SystemUser.ID,
    externalId = SystemUser.EXTERNAL_ID,
    email = SystemUser.EMAIL,
    displayName = SystemUser.DISPLAY_NAME,
    tenantMemberships = mapOf(tenantKey to TenantRole.entries.toSet()),
    currentTenantId = tenantKey,
)

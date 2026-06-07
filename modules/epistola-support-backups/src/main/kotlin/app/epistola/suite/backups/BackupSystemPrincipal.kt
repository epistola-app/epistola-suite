package app.epistola.suite.backups

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.SystemUser
import app.epistola.suite.security.TenantRole

/**
 * System principal for background catalog-backup work (the daily scheduler, restore). These run
 * on scheduler threads outside any HTTP request, so they have no [app.epistola.suite.security.SecurityContext];
 * this grants all roles for the one tenant being processed so the permission-gated snapshot/import
 * commands authorize. Mirrors the feedback sync system principal.
 */
internal fun backupSystemPrincipal(tenantKey: TenantKey): EpistolaPrincipal = EpistolaPrincipal(
    userId = SystemUser.ID,
    externalId = SystemUser.EXTERNAL_ID,
    email = SystemUser.EMAIL,
    displayName = SystemUser.DISPLAY_NAME,
    tenantMemberships = mapOf(tenantKey to TenantRole.entries.toSet()),
    currentTenantId = tenantKey,
)

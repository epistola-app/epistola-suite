package app.epistola.suite.feedback.sync

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.SystemUser
import app.epistola.suite.security.TenantRole

/**
 * System principal for background feedback sync (poll apply, retry sweeps). These run on
 * scheduler threads outside any HTTP request scope, so they have no [SecurityContext];
 * this grants all roles for the one tenant being processed so the mediator's authorization
 * checks pass. Mirrors `JobPoller`'s background-job principal.
 */
internal fun feedbackSystemPrincipal(tenantKey: TenantKey): EpistolaPrincipal = EpistolaPrincipal(
    userId = SystemUser.ID,
    externalId = SystemUser.EXTERNAL_ID,
    email = SystemUser.EMAIL,
    displayName = SystemUser.DISPLAY_NAME,
    tenantMemberships = mapOf(tenantKey to TenantRole.entries.toSet()),
    currentTenantId = tenantKey,
)

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.feedback.sync

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.SystemUser

/**
 * System principal for background feedback sync (poll apply, retry sweeps). These run on
 * scheduler threads outside any HTTP request scope, so they have no [SecurityContext];
 * this grants all roles for the one tenant being processed so the mediator's authorization
 * checks pass. Mirrors `JobPoller`'s background-job principal.
 */
internal fun feedbackSystemPrincipal(tenantKey: TenantKey): EpistolaPrincipal = SystemUser.principalForTenant(tenantKey)

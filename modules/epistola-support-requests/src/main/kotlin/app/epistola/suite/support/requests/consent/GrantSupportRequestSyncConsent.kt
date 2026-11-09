// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.consent

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.support.connection.SupportConnections
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Gives the tenant's consent to send its feedback to Epistola support, from now on. Feedback
 * submitted before stays in the installation: consent is not applied to it retroactively.
 * Granting again while in force changes nothing, except that it is now the tenant's own choice: it
 * no longer follows the installation default ([SupportRequestSharingDefault]).
 */
data class GrantSupportRequestSyncConsent(
    override val tenantKey: TenantKey,
    /** The connection the tenant shares support requests with. */
    val connectionId: UUID = SupportConnections.DISCOVERED_ID,
) : Command<SupportRequestSyncConsent>,
    RequiresPermission {
    override val permission get() = Permission.INTEGRATIONS_MANAGE
}

@Component
class GrantSupportRequestSyncConsentHandler(
    private val jdbi: Jdbi,
) : CommandHandler<GrantSupportRequestSyncConsent, SupportRequestSyncConsent> {
    override fun handle(command: GrantSupportRequestSyncConsent): SupportRequestSyncConsent = jdbi.withHandleUnchecked { handle ->
        // The user row may be absent for a system principal; the stamp is then null. Its own choice:
        // it no longer follows the installation default.
        SupportRequestConsentWrites.grant(handle, command.tenantKey, SecurityContext.current().userId.value, EpistolaClock.offsetDateTime(), followsDefault = false, connectionId = command.connectionId)
        handle.createQuery(SELECT_SUPPORT_REQUEST_SYNC_CONSENT)
            .bind("tenantKey", command.tenantKey)
            .bind("connectionId", command.connectionId)
            .mapTo(SupportRequestSyncConsent::class.java)
            .one()
    }
}

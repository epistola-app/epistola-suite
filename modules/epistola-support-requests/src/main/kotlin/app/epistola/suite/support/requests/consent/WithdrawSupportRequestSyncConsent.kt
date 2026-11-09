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
import app.epistola.suite.support.requests.SyncStatus
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Withdraws the tenant's consent: from now on nothing of its feedback is sent to Epistola support,
 * and replies from Epistola are no longer applied. Everything still waiting to be sent stays here,
 * in the same transaction: feedback still [SyncStatus.PENDING] or [SyncStatus.FAILED] under a grant
 * becomes [SyncStatus.NOT_CONFIGURED] (feedback that read FAILED under no grant, as the retired hub
 * left it, was never waiting and is not counted), a status change not sent yet is no longer marked
 * unsent, and comments and screenshots lose the grant they were written under, so granting consent
 * again later does not send them either. Feedback Epistola already received carries on after a new grant: what is written
 * about it from then on is sent. What Epistola already received stays there.
 *
 * It is the tenant's own choice, also when it never consented: from then on it no longer follows the
 * installation default ([SupportRequestSharingDefault]).
 *
 * Its first statement locks the consent row until the transaction ends. Every write that may be sent
 * reads that row `FOR SHARE` (`lockedConsentInForce`): one that runs meanwhile waits and then finds
 * consent withdrawn, and one that ran first has committed before the statements below run, which
 * then stop what it wrote.
 */
data class WithdrawSupportRequestSyncConsent(
    override val tenantKey: TenantKey,
    /** The connection the tenant stops sharing support requests with. */
    val connectionId: UUID = SupportConnections.DISCOVERED_ID,
) : Command<SupportRequestSyncConsentWithdrawal>,
    RequiresPermission {
    override val permission get() = Permission.INTEGRATIONS_MANAGE
}

@Component
class WithdrawSupportRequestSyncConsentHandler(
    private val jdbi: Jdbi,
) : CommandHandler<WithdrawSupportRequestSyncConsent, SupportRequestSyncConsentWithdrawal> {
    override fun handle(command: WithdrawSupportRequestSyncConsent): SupportRequestSyncConsentWithdrawal = jdbi.withHandleUnchecked { handle ->
        val stopped = SupportRequestConsentWrites.withdrawOwn(handle, command.tenantKey, SecurityContext.current().userId.value, EpistolaClock.offsetDateTime(), command.connectionId)
        val consent = handle.createQuery(SELECT_SUPPORT_REQUEST_SYNC_CONSENT)
            .bind("tenantKey", command.tenantKey)
            .bind("connectionId", command.connectionId)
            .mapTo(SupportRequestSyncConsent::class.java)
            .findOne()
            .orElse(SupportRequestSyncConsent.none(command.tenantKey))
        SupportRequestSyncConsentWithdrawal(consent, stopped)
    }
}

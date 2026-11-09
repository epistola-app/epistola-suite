// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.sync

import app.epistola.suite.mediator.EventHandler
import app.epistola.suite.mediator.EventPhase
import app.epistola.suite.mediator.query
import app.epistola.suite.support.requests.SyncStatus
import app.epistola.suite.support.requests.commands.UpdateSupportRequestStatus
import app.epistola.suite.support.requests.queries.GetSupportRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Starts sending a local status change ([UpdateSupportRequestStatus], raised by the UI) to Epistola
 * support in the background ([SupportRequestSyncKick]). [UpdateSupportRequestStatus] marks the status unsent
 * (`status_unsent`) for feedback already there and exchanged under the consent in force, so the retry
 * sweep sends it whenever this does not: a change made while the service is down is not lost. A
 * change made while consent is withdrawn is not marked, so it is never sent. Inbound status changes
 * ([app.epistola.suite.support.requests.commands.SyncSupportRequestStatus]) do not fire this, so a polled-back
 * status does not bounce straight back.
 */
@Component
class OnSupportRequestStatusChanged(
    private val gate: SupportRequestSyncGate,
    private val kick: SupportRequestSyncKick,
) : EventHandler<UpdateSupportRequestStatus> {
    private val log = LoggerFactory.getLogger(javaClass)

    override val phase = EventPhase.IMMEDIATE

    override fun on(event: UpdateSupportRequestStatus, result: Any?) {
        // No row changed (status unchanged or feedback missing): nothing to send.
        if (result != true) return
        val supportRequest = GetSupportRequest(event.id).query() ?: return
        if (supportRequest.syncStatus != SyncStatus.SYNCED || supportRequest.externalRef == null) return
        if (gate.mayExchange(supportRequest)) kick.kick()
    }
}

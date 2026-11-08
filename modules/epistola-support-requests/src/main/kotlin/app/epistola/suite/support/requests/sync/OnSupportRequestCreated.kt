// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.sync

import app.epistola.suite.mediator.EventHandler
import app.epistola.suite.mediator.EventPhase
import app.epistola.suite.mediator.execute
import app.epistola.suite.support.requests.SupportRequest
import app.epistola.suite.support.requests.SyncStatus
import app.epistola.suite.support.requests.commands.CreateSupportRequest
import app.epistola.suite.support.requests.commands.UpdateSupportRequestSyncRef
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Starts sending new feedback to Epistola support right after it is stored, when the tenant
 * consented and the service takes it now ([SupportRequestSyncGate]): in the background ([SupportRequestSyncKick]),
 * never on the user's request. Otherwise it stays PENDING for the retry sweep.
 */
@Component
class OnSupportRequestCreated(
    private val gate: SupportRequestSyncGate,
    private val kick: SupportRequestSyncKick,
) : EventHandler<CreateSupportRequest> {
    private val log = LoggerFactory.getLogger(javaClass)

    override val phase = EventPhase.IMMEDIATE

    override fun on(event: CreateSupportRequest, result: Any?) {
        val supportRequest = result as? SupportRequest ?: run {
            log.warn("Expected Feedback result from CreateFeedback but got {}", result?.javaClass?.name)
            return
        }
        // PENDING only when sending was allowed at creation (consent and a wired target).
        if (supportRequest.syncStatus != SyncStatus.PENDING) return
        if (gate.mayExchange(supportRequest)) kick.kick()
    }
}

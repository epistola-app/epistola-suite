// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.sync

import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.connection.SupportBackgroundWork
import app.epistola.suite.support.connection.SupportConnection
import app.epistola.suite.support.connection.SupportConnectionRegistry
import app.epistola.suite.support.connection.lastContactFailed
import app.epistola.suite.support.requests.SupportRequest
import app.epistola.suite.support.requests.consent.IsSupportRequestExchangedUnderConsent
import app.epistola.suite.support.requests.consent.IsSupportRequestSyncConsentInForce
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/**
 * The one place that decides whether feedback may be sent now. Every driver (the three IMMEDIATE
 * event handlers, the asset handler, the retry sweep and the poll) asks it before calling the port.
 */
@Component
class SupportRequestSyncGate(
    private val port: SupportRequestSyncPort,
    private val connection: SupportConnection,
    private val ports: ObjectProvider<SupportRequestSyncPorts>,
    private val registry: ObjectProvider<SupportConnectionRegistry>,
) {
    /**
     * A real target is wired, it takes feedback now (the `feedback` scope is granted), and the last
     * contact with the support service did not fail. Cheap: no network I/O.
     */
    fun canSendNow(): Boolean = port.isEnabled() && port.isReady() && !connection.lastContactFailed()

    /** [canSendNow] for support connection [connectionId] (ADR 0053 step 3): its port is wired and ready, and its last contact did not fail. */
    fun canSendNow(connectionId: UUID): Boolean {
        if (connectionId == REQUESTS_CONNECTION) return canSendNow()
        val target = ports.ifAvailable?.port(connectionId) ?: return false
        val other = registry.ifAvailable?.find(connectionId) ?: return false
        return target.isEnabled() && target.isReady() && !other.lastContactFailed()
    }

    /** [canSendNow], and [tenantKey]'s consent to send its feedback is in force. */
    fun maySend(tenantKey: TenantKey): Boolean = canSendNow() && IsSupportRequestSyncConsentInForce(tenantKey).query()

    /**
     * [canSendNow], and [feedback] is exchanged under the consent in force: what is said or added
     * about feedback sent before a withdrawal, or synced by the retired hub, stays here.
     */
    fun mayExchange(supportRequest: SupportRequest): Boolean = canSendNow() && IsSupportRequestExchangedUnderConsent(SupportRequestId(supportRequest.id, TenantId(supportRequest.tenantKey))).query()
}

/**
 * Starts a sending round on [SupportBackgroundWork] right after a change, instead of talking to
 * the support service on the request: the user's page never waits on it, and a slow or failing
 * service cannot make anyone resubmit. A round already running goes round once more. Without the
 * retry sweep (`epistola.support.requests.sync.enabled=false`) nothing is sent.
 */
@Component
class SupportRequestSyncKick(
    private val work: SupportBackgroundWork,
    private val sweep: ObjectProvider<SupportRequestSyncScheduler>,
) {
    fun kick() {
        val scheduler = sweep.ifAvailable ?: return
        val start = { work.launch(SupportRequestSyncScheduler.WORK_KEY, again = true) { scheduler.retryPendingSync() } }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // Only once the change is committed, so the round sees it.
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        start()
                    }
                },
            )
        } else {
            start()
        }
    }
}

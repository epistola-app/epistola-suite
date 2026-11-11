// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.sync

import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.EventHandler
import app.epistola.suite.mediator.EventPhase
import app.epistola.suite.mediator.query
import app.epistola.suite.support.requests.SupportRequestAsset
import app.epistola.suite.support.requests.SyncStatus
import app.epistola.suite.support.requests.commands.AddSupportRequestAsset
import app.epistola.suite.support.requests.queries.GetSupportRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Starts sending a newly stored asset (the screenshot) of feedback already at Epistola support, in
 * the background ([SupportRequestSyncKick]), when the tenant consented and the service takes it now.
 * Otherwise it stays unsent for the retry sweep, which also sends the assets of feedback that
 * reaches the service later.
 */
@Component
class OnSupportRequestAssetAdded(
    private val gate: SupportRequestSyncGate,
    private val kick: SupportRequestSyncKick,
) : EventHandler<AddSupportRequestAsset> {
    private val log = LoggerFactory.getLogger(javaClass)

    override val phase = EventPhase.IMMEDIATE

    override fun on(event: AddSupportRequestAsset, result: Any?) {
        if (result !is SupportRequestAsset) {
            log.warn("Expected FeedbackAsset result from AddFeedbackAsset but got another type={}", result?.javaClass?.name)
            return
        }
        val supportRequest = GetSupportRequest(SupportRequestId(event.id.supportRequestKey, TenantId(event.id.tenantKey))).query() ?: return
        if (supportRequest.syncStatus != SyncStatus.SYNCED || supportRequest.externalRef == null) return
        if (gate.mayExchange(supportRequest)) kick.kick()
    }
}

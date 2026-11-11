// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.sync

import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.EventHandler
import app.epistola.suite.mediator.EventPhase
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.requests.SupportRequestComment
import app.epistola.suite.support.requests.SyncStatus
import app.epistola.suite.support.requests.commands.AddSupportRequestComment
import app.epistola.suite.support.requests.commands.RecordSupportRequestCommentSyncFailure
import app.epistola.suite.support.requests.commands.UpdateSupportRequestCommentExternalRef
import app.epistola.suite.support.requests.queries.GetSupportRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Starts sending a local comment to Epistola support right after it is stored, in the background
 * ([SupportRequestSyncKick]), when the feedback is there and exchanged under the consent in force. Only
 * fires for [AddSupportRequestComment] (local comments): inbound comments
 * ([app.epistola.suite.support.requests.commands.SyncSupportRequestComment]) do not, avoiding a sync loop. The
 * sweep stores the external comment id, so the inbound poll skips the comment.
 */
@Component
class OnSupportRequestCommentAdded(
    private val gate: SupportRequestSyncGate,
    private val kick: SupportRequestSyncKick,
) : EventHandler<AddSupportRequestComment> {
    private val log = LoggerFactory.getLogger(javaClass)

    override val phase = EventPhase.IMMEDIATE

    override fun on(event: AddSupportRequestComment, result: Any?) {
        if (result !is SupportRequestComment) {
            log.warn("Expected FeedbackComment result from AddFeedbackComment but got another type={}", result?.javaClass?.name)
            return
        }
        val supportRequest = GetSupportRequest(SupportRequestId(event.id.supportRequestKey, TenantId(event.id.tenantKey))).query() ?: return
        if (supportRequest.syncStatus != SyncStatus.SYNCED || supportRequest.externalRef == null) return
        if (gate.mayExchange(supportRequest)) kick.kick()
    }
}

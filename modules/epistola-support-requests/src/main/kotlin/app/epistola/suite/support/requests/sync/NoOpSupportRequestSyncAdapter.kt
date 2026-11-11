// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.sync

import app.epistola.suite.common.ids.SupportRequestAssetKey
import app.epistola.suite.support.requests.SupportRequest
import app.epistola.suite.support.requests.SupportRequestAssetContent
import app.epistola.suite.support.requests.SupportRequestComment
import app.epistola.suite.support.requests.SupportRequestStatus
import org.slf4j.LoggerFactory

/**
 * No-op implementation used when no external sync target is wired (the support tier is
 * disabled). [isEnabled] returns false so the drivers skip syncing entirely, and feedback
 * stays local. Registered as a fallback bean via [SupportRequestSyncFallbackConfiguration].
 */
class NoOpSupportRequestSyncAdapter : SupportRequestSyncPort {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun isEnabled(): Boolean = false

    override fun createTicket(supportRequest: SupportRequest): SyncResult {
        log.debug("No-op sync: createTicket called feedback={}", supportRequest.id)
        return SyncResult(externalRef = "noop", externalUrl = "")
    }

    override fun uploadAsset(supportRequest: SupportRequest, assetKey: SupportRequestAssetKey, asset: SupportRequestAssetContent) {
        log.debug("No-op sync: uploadAsset called feedback={}", supportRequest.id)
    }

    override fun addComment(supportRequest: SupportRequest, comment: SupportRequestComment): ExternalCommentRef {
        log.debug("No-op sync: addComment called feedback={}", supportRequest.id)
        return ExternalCommentRef(externalCommentId = "0")
    }

    override fun updateStatus(supportRequest: SupportRequest, status: SupportRequestStatus) {
        log.debug("No-op sync: updateStatus called feedback={}", supportRequest.id)
    }

    override fun fetchUpdates(acknowledgeUpTo: Long?): ExternalUpdatePage {
        log.debug("No-op sync: fetchUpdates called, returning empty page")
        return ExternalUpdatePage(updates = emptyList(), lastSequence = null, hasMore = false)
    }
}

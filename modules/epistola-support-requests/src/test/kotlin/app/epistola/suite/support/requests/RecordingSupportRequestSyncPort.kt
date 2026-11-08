// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests

import app.epistola.suite.common.ids.SupportRequestAssetKey
import app.epistola.suite.support.connection.SupportUnavailableException
import app.epistola.suite.support.requests.sync.ExternalCommentRef
import app.epistola.suite.support.requests.sync.ExternalUpdatePage
import app.epistola.suite.support.requests.sync.SupportRequestSyncPort
import app.epistola.suite.support.requests.sync.SyncResult

/**
 * In-memory [SupportRequestSyncPort] for driver tests: records outbound calls and returns queued
 * inbound pages, with switches to simulate failures. Reset with [reset] in `@BeforeEach`.
 */
class RecordingSupportRequestSyncPort : SupportRequestSyncPort {
    /** One asset as the port received it. */
    data class UploadedAsset(val supportRequest: SupportRequest, val assetKey: SupportRequestAssetKey, val content: SupportRequestAssetContent)

    /** The kinds of item the port sends. */
    enum class Outbound { SUPPORT_REQUEST, ASSET, COMMENT, STATUS }

    /**
     * Runs as a call starts, before anything is recorded: lets a test change state while a
     * sending round is under way (a withdrawal landing mid-round).
     */
    @Volatile
    var beforeSend: ((Outbound, SupportRequest) -> Unit)? = null

    var enabled: Boolean = true
    var ready: Boolean = true

    val createdTickets = mutableListOf<SupportRequest>()
    val uploadedAssets = mutableListOf<UploadedAsset>()
    val addedComments = mutableListOf<Pair<SupportRequest, SupportRequestComment>>()
    val statusUpdates = mutableListOf<Pair<SupportRequest, SupportRequestStatus>>()

    /** The acknowledgement each [fetchUpdates] call carried; null when it acknowledged nothing. */
    val fetchCalls = mutableListOf<Long?>()

    var failCreate: Boolean = false
    var failAddComment: Boolean = false

    /** Asset uploads fail as if the support service were down: "not now", no attempt counted. */
    var assetServiceUnavailable: Boolean = false

    /** Asset uploads fail as if the service refused this asset: an attempt is counted. */
    var failUploadAsset: Boolean = false

    /** Pages returned by successive [fetchUpdates] calls; empty page once drained. */
    val pages: ArrayDeque<ExternalUpdatePage> = ArrayDeque()

    fun reset() {
        enabled = true
        ready = true
        createdTickets.clear()
        uploadedAssets.clear()
        addedComments.clear()
        statusUpdates.clear()
        fetchCalls.clear()
        pages.clear()
        failCreate = false
        failAddComment = false
        assetServiceUnavailable = false
        failUploadAsset = false
        beforeSend = null
    }

    override fun isEnabled(): Boolean = enabled

    override fun isReady(): Boolean = ready

    override fun createTicket(supportRequest: SupportRequest): SyncResult {
        beforeSend?.invoke(Outbound.SUPPORT_REQUEST, supportRequest)
        createdTickets += supportRequest
        if (failCreate) throw RuntimeException("simulated createTicket failure")
        return SyncResult(externalRef = "hub-${supportRequest.id.value}", externalUrl = "https://hub/${supportRequest.id.value}")
    }

    override fun uploadAsset(supportRequest: SupportRequest, assetKey: SupportRequestAssetKey, asset: SupportRequestAssetContent) {
        beforeSend?.invoke(Outbound.ASSET, supportRequest)
        if (assetServiceUnavailable) throw SupportUnavailableException("simulated: support service down")
        if (failUploadAsset) throw IllegalArgumentException("simulated: asset refused")
        uploadedAssets += UploadedAsset(supportRequest, assetKey, asset)
    }

    override fun addComment(supportRequest: SupportRequest, comment: SupportRequestComment): ExternalCommentRef {
        beforeSend?.invoke(Outbound.COMMENT, supportRequest)
        if (failAddComment) throw RuntimeException("simulated addComment failure")
        addedComments += supportRequest to comment
        return ExternalCommentRef(externalCommentId = "ext-${comment.id.value}")
    }

    override fun updateStatus(supportRequest: SupportRequest, status: SupportRequestStatus) {
        beforeSend?.invoke(Outbound.STATUS, supportRequest)
        statusUpdates += supportRequest to status
    }

    override fun fetchUpdates(acknowledgeUpTo: Long?): ExternalUpdatePage {
        fetchCalls += acknowledgeUpTo
        return if (pages.isEmpty()) {
            ExternalUpdatePage(updates = emptyList(), lastSequence = null, hasMore = false)
        } else {
            pages.removeFirst()
        }
    }
}

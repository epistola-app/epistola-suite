// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.feedback

import app.epistola.suite.feedback.sync.ExternalCommentRef
import app.epistola.suite.feedback.sync.ExternalUpdatePage
import app.epistola.suite.feedback.sync.FeedbackSyncPort
import app.epistola.suite.feedback.sync.SyncResult

/**
 * In-memory [FeedbackSyncPort] for driver tests: records outbound calls and returns queued
 * inbound pages, with switches to simulate failures. Reset with [reset] in `@BeforeEach`.
 */
class RecordingFeedbackSyncPort : FeedbackSyncPort {
    var enabled: Boolean = true

    val createdTickets = mutableListOf<Feedback>()
    val addedComments = mutableListOf<Pair<Feedback, FeedbackComment>>()
    val statusUpdates = mutableListOf<Pair<Feedback, FeedbackStatus>>()
    val fetchCalls = mutableListOf<Long>()

    var failCreate: Boolean = false
    var failAddComment: Boolean = false

    /** Pages returned by successive [fetchUpdates] calls; empty page once drained. */
    val pages: ArrayDeque<ExternalUpdatePage> = ArrayDeque()

    fun reset() {
        enabled = true
        createdTickets.clear()
        addedComments.clear()
        statusUpdates.clear()
        fetchCalls.clear()
        pages.clear()
        failCreate = false
        failAddComment = false
    }

    override fun isEnabled(): Boolean = enabled

    override fun createTicket(feedback: Feedback, assets: List<FeedbackAssetContent>): SyncResult {
        createdTickets += feedback
        if (failCreate) throw RuntimeException("simulated createTicket failure")
        return SyncResult(externalRef = "hub-${feedback.id.value}", externalUrl = "https://hub/${feedback.id.value}")
    }

    override fun addComment(feedback: Feedback, comment: FeedbackComment): ExternalCommentRef {
        if (failAddComment) throw RuntimeException("simulated addComment failure")
        addedComments += feedback to comment
        return ExternalCommentRef(externalCommentId = "ext-${comment.id.value}")
    }

    override fun updateStatus(feedback: Feedback, status: FeedbackStatus) {
        statusUpdates += feedback to status
    }

    override fun fetchUpdates(afterSeq: Long): ExternalUpdatePage {
        fetchCalls += afterSeq
        return if (pages.isEmpty()) {
            ExternalUpdatePage(updates = emptyList(), nextSeq = afterSeq, hasMore = false)
        } else {
            pages.removeFirst()
        }
    }
}

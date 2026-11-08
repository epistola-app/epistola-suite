// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.sync

import app.epistola.suite.common.ids.SupportRequestAssetKey
import app.epistola.suite.support.connection.SupportRefusedException
import app.epistola.suite.support.connection.isSupportUnavailable
import app.epistola.suite.support.requests.SupportRequest
import app.epistola.suite.support.requests.SupportRequestAssetContent
import app.epistola.suite.support.requests.SupportRequestComment
import app.epistola.suite.support.requests.SupportRequestStatus

/**
 * Port for syncing feedback to an external system.
 *
 * The production implementation sends feedback to the Epistola support service over the
 * support connection (`docs/support-protocol.md`); [NoOpSupportRequestSyncAdapter] is the default
 * until a bean of this type replaces it. Authentication and the target are
 * installation-wide — there is no per-tenant configuration — so the methods take only the
 * feedback/comment being synced. Each [SupportRequest] carries its own `tenantKey` and (once
 * synced) `externalRef`.
 */
interface SupportRequestSyncPort {
    /** True when a real sync target is wired (i.e. not the no-op). Gates the drivers. */
    fun isEnabled(): Boolean

    /**
     * True once the sync target is actually usable — e.g. the installation has finished
     * registering with the support service and holds the `feedback` scope. The drivers check this in addition to
     * [isEnabled] so no remote call is attempted before registration completes. Defaults to
     * true for in-memory/no-op implementations that have nothing to wait for.
     */
    fun isReady(): Boolean = true

    /**
     * Sends the feedback itself, without its assets: they are stored after the feedback is created
     * and go separately through [uploadAsset]. Idempotent on the feedback's own id.
     */
    fun createTicket(supportRequest: SupportRequest): SyncResult

    /**
     * Sends one asset (a screenshot) of feedback already synced ([Feedback.externalRef] non-null).
     * Idempotent on [assetKey].
     */
    fun uploadAsset(supportRequest: SupportRequest, assetKey: SupportRequestAssetKey, asset: SupportRequestAssetContent)

    /** [feedback] must already be synced ([Feedback.externalRef] non-null). Idempotent on the comment's id. */
    fun addComment(supportRequest: SupportRequest, comment: SupportRequestComment): ExternalCommentRef

    /** [feedback] must already be synced ([Feedback.externalRef] non-null). */
    fun updateStatus(supportRequest: SupportRequest, status: SupportRequestStatus)

    /**
     * Acknowledges the inbound updates handled up to [acknowledgeUpTo] (null: nothing new handled),
     * then returns the next page after what this installation acknowledged, across all its tenants.
     * The connection keeps that position, as with collecting generated documents
     * (`docs/support-protocol.md`, "Changes from Epistola"). Each [ExternalUpdate] carries its tenant
     * and its own [ExternalUpdate.seq].
     */
    fun fetchUpdates(acknowledgeUpTo: Long?): ExternalUpdatePage
}

data class SyncResult(
    val externalRef: String,
    val externalUrl: String,
)

data class ExternalCommentRef(
    val externalCommentId: String,
)

/**
 * The sync target is there but will not take this now — the token lacks the `feedback` scope, or the
 * registration was refused. Like an unavailable service, the item is retried later without counting
 * an attempt against it.
 */
class SupportRequestSyncNotReadyException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * True when a sync failure means "not now" rather than "this item is wrong": the drivers leave the
 * item for a later round without counting an attempt, and stop the round.
 */
fun Throwable.isSupportRequestSyncDeferred(): Boolean = isSupportUnavailable() ||
    generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.take(16).any {
        it is SupportRequestSyncNotReadyException || it is SupportRefusedException
    }

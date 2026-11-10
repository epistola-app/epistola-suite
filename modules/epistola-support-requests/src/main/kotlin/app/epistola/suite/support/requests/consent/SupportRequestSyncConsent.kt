// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.consent

import app.epistola.suite.common.ids.TenantKey
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A tenant's consent to send its feedback to Epistola support (`support_sharing_choice`, section `requests`).
 *
 * This is the only thing that lets feedback leave the installation. The `support-requests` feature
 * toggle is not consent: it defaults to on and only shows or hides the feedback UI.
 *
 * @property consentedAt when consent was last given; null when it never was.
 * @property consentedBy display name of who gave it; null when never given or that user is gone.
 * @property withdrawnAt when the last consent was withdrawn; null while it is in force.
 * @property followsDefault the tenant made no choice of its own and follows the installation default
 *   ([SupportRequestSharingDefault]); then [consentedBy] and [withdrawnBy] name the administrator who set it.
 */
data class SupportRequestSyncConsent(
    val tenantKey: TenantKey,
    val consentedAt: OffsetDateTime?,
    val consentedBy: String?,
    val withdrawnAt: OffsetDateTime?,
    val withdrawnBy: String?,
    val followsDefault: Boolean = false,
) {
    /** True while the tenant's feedback may be sent to Epistola support. */
    val inForce: Boolean get() = consentedAt != null && withdrawnAt == null

    companion object {
        /** No row: the tenant made no choice of its own, so it follows the installation default. */
        fun none(tenantKey: TenantKey) = SupportRequestSyncConsent(tenantKey, null, null, null, null, followsDefault = true)
    }
}

/**
 * The result of withdrawing consent.
 *
 * @property stoppedSupportRequest feedback that was waiting to be sent (`PENDING`, or `FAILED` after refusals)
 *   and now stays in the installation.
 */
data class SupportRequestSyncConsentWithdrawal(
    val consent: SupportRequestSyncConsent,
    val stoppedSupportRequest: Int,
)

/** Connection [id] as an SQL literal, for the predicates below; a column such as `d.connection_id` works as well. */
internal fun connectionSql(id: UUID): String = "'$id'::uuid"

/** SQL that reads one tenant's [SupportRequestSyncConsent] row with the names of who changed it; binds `tenantKey` and `connectionId`. */
internal val SELECT_SUPPORT_REQUEST_SYNC_CONSENT = """
    SELECT c.tenant_key, c.given_at AS consented_at, cu.display_name AS consented_by,
           c.withdrawn_at, wu.display_name AS withdrawn_by, c.follows_default
    FROM support_sharing_choice c
    LEFT JOIN users cu ON cu.id = c.given_by
    LEFT JOIN users wu ON wu.id = c.withdrawn_by
    WHERE c.connection_id = :connectionId AND c.section = '$REQUESTS_SECTION' AND c.tenant_key = :tenantKey
"""

/**
 * SQL predicate, for a query with the tenant's key in [tenantKeyColumn], that the tenant's consent
 * is in force. The sync sweeps filter on it in SQL so they never load what they may not send.
 */
internal fun consentInForce(tenantKeyColumn: String, connection: String): String = """
    EXISTS (SELECT 1 FROM support_sharing_choice fsc
            WHERE fsc.connection_id = $connection AND fsc.section = '$REQUESTS_SECTION' AND fsc.tenant_key = $tenantKeyColumn AND fsc.withdrawn_at IS NULL)
"""

/**
 * For a statement that **writes** something that may be sent: [consentInForce], reading the tenant's
 * consent row `FOR SHARE`. A withdrawal holds that row locked from its first statement until it
 * commits, so a write that runs meanwhile waits for it and then reads consent withdrawn, and a write
 * that ran first is committed before the withdrawal goes on, which then stops what it wrote. Without
 * the lock, under READ COMMITTED, a write landing while a withdrawal is under way reads the consent
 * still in force and the withdrawal's statements miss its uncommitted row: it would be sent once
 * consent is given again. Not for queries, which may run read-only.
 */
internal fun lockedConsentInForce(tenantKeyColumn: String, connection: String): String = """
    EXISTS (SELECT 1 FROM support_sharing_choice fsc
            WHERE fsc.connection_id = $connection AND fsc.section = '$REQUESTS_SECTION' AND fsc.tenant_key = $tenantKeyColumn AND fsc.withdrawn_at IS NULL
            FOR SHARE)
"""

/**
 * SQL predicate: the request aliased [requestAlias] is exchanged with the connection of its delivery
 * [deliveryAlias] (ADR 0053 §4): the tenant shares support requests with that connection now. A
 * delivery exists only when the tenant shared when the request was handed to it, and then what it held
 * went along ([SupportRequestLedger]).
 */
internal fun exchangedWith(requestAlias: String, deliveryAlias: String): String = consentInForce("$requestAlias.tenant_key", "$deliveryAlias.connection_id")

/**
 * SQL predicate: a message or asset may go to the connection of its delivery, its ledger row for that
 * connection aliased [ledgerAlias] (a LEFT JOIN of `support_request_delivered_items`): the row exists,
 * written while the tenant shared with that connection ([SupportRequestLedger]).
 */
internal fun mayGoTo(ledgerAlias: String): String = "$ledgerAlias.item_id IS NOT NULL"

/**
 * SQL predicate, for a statement that writes: the request aliased [requestAlias] is exchanged with
 * connection [connectionId], reading the consent `FOR SHARE` ([exchangedWith] for one connection).
 */
internal fun exchangedWithLocked(requestAlias: String, connectionId: UUID): String = lockedConsentInForce("$requestAlias.tenant_key", connectionSql(connectionId))

/** [exchangedWith] for one connection, for a query. */
internal fun exchangedWithConnection(requestAlias: String, connectionId: UUID): String = consentInForce("$requestAlias.tenant_key", connectionSql(connectionId))

/** [exchangedWith] for a statement that writes, reading the consent `FOR SHARE`. */
internal fun exchangedWithLockedRow(requestAlias: String, deliveryAlias: String): String = lockedConsentInForce("$requestAlias.tenant_key", "$deliveryAlias.connection_id")

/**
 * Marks [requestAlias]'s new status as still to send to every connection it is delivered to and
 * exchanged with, except [except] (where the change came from, an SQL expression or `NULL`): a change
 * made while a connection is not shared with is never sent there. Binds `tenantKey` and `id`.
 */
internal fun markStatusUnsentSql(except: String = "NULL"): String = """
    UPDATE support_request_deliveries d
    SET status_unsent = (d.connection_id IS DISTINCT FROM $except AND ${exchangedWithLockedRow("f", "d")}),
        next_attempt_at = NULL, status_attempts = 0
    FROM support_requests f
    WHERE f.tenant_key = d.tenant_key AND f.id = d.request_id
      AND d.tenant_key = :tenantKey AND d.request_id = :id
      AND d.remote_ref IS NOT NULL
"""

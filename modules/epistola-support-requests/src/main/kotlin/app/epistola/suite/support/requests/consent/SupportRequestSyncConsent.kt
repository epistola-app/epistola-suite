// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.consent

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.support.connection.SupportConnections
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

/**
 * The connection support requests went over before they had one per connection: Epistola support's.
 * What reads or writes a tenant's consent takes the connection; this is its default (ADR 0053 step 3).
 */
internal val REQUESTS_CONNECTION: UUID = SupportConnections.DISCOVERED_ID

/** [REQUESTS_CONNECTION] as an SQL literal, for the predicates below, which are built into other statements. */
internal val REQUESTS_CONNECTION_SQL = connectionSql(REQUESTS_CONNECTION)

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
internal fun consentInForce(tenantKeyColumn: String, connection: String = REQUESTS_CONNECTION_SQL): String = """
    EXISTS (SELECT 1 FROM support_sharing_choice fsc
            WHERE fsc.connection_id = $connection AND fsc.section = '$REQUESTS_SECTION' AND fsc.tenant_key = $tenantKeyColumn AND fsc.withdrawn_at IS NULL)
"""

/**
 * SQL expression: the `grant_id` of [tenantKeyColumn]'s consent in force (one grant), or NULL.
 * What `support_requests.sync_consent_id` is set to when feedback is submitted.
 */
internal fun consentInForceId(tenantKeyColumn: String, connection: String = REQUESTS_CONNECTION_SQL): String = """
    (SELECT fsc.grant_id FROM support_sharing_choice fsc
     WHERE fsc.connection_id = $connection AND fsc.section = '$REQUESTS_SECTION' AND fsc.tenant_key = $tenantKeyColumn AND fsc.withdrawn_at IS NULL)
"""

/**
 * SQL predicate, for a query over `support_requests` aliased [feedbackAlias], that the feedback is exchanged
 * with Epistola support: the tenant's consent is in force, and the feedback was submitted under a
 * grant of consent (any grant: after a withdrawal and a new grant it carries on). Feedback submitted
 * without consent, or synced by the retired hub, is not: its comments, status changes and assets stay
 * in the installation. A comment or an asset also needs [createdUnderConsent].
 */
internal fun exchangedUnderConsent(supportRequestAlias: String, connection: String = REQUESTS_CONNECTION_SQL): String = """
    ($supportRequestAlias.sync_consent_id IS NOT NULL
     AND EXISTS (SELECT 1 FROM support_sharing_choice fsc
                 WHERE fsc.connection_id = $connection AND fsc.section = '$REQUESTS_SECTION' AND fsc.tenant_key = $supportRequestAlias.tenant_key AND fsc.withdrawn_at IS NULL))
"""

/**
 * SQL predicate, for a `support_request_comments` or `support_request_assets` row aliased [alias], that it was
 * written while sharing was on and was not stopped by a withdrawal before it was sent. What was
 * written while sharing was off is never sent, also once consent is given again.
 */
internal fun createdUnderConsent(alias: String): String = "$alias.sync_consent_id IS NOT NULL"

/**
 * For a statement that **writes** something that may be sent: [consentInForce], reading the tenant's
 * consent row `FOR SHARE`. A withdrawal holds that row locked from its first statement until it
 * commits, so a write that runs meanwhile waits for it and then reads consent withdrawn, and a write
 * that ran first is committed before the withdrawal goes on, which then stops what it wrote. Without
 * the lock, under READ COMMITTED, a write landing while a withdrawal is under way reads the consent
 * still in force and the withdrawal's statements miss its uncommitted row: it would be sent once
 * consent is given again. Not for queries, which may run read-only.
 */
internal fun lockedConsentInForce(tenantKeyColumn: String, connection: String = REQUESTS_CONNECTION_SQL): String = """
    EXISTS (SELECT 1 FROM support_sharing_choice fsc
            WHERE fsc.connection_id = $connection AND fsc.section = '$REQUESTS_SECTION' AND fsc.tenant_key = $tenantKeyColumn AND fsc.withdrawn_at IS NULL
            FOR SHARE)
"""

/** [consentInForceId] for a statement that writes, locking as [lockedConsentInForce] does. */
internal fun lockedConsentInForceId(tenantKeyColumn: String, connection: String = REQUESTS_CONNECTION_SQL): String = """
    (SELECT fsc.grant_id FROM support_sharing_choice fsc
     WHERE fsc.connection_id = $connection AND fsc.section = '$REQUESTS_SECTION' AND fsc.tenant_key = $tenantKeyColumn AND fsc.withdrawn_at IS NULL
     FOR SHARE)
"""

/** [exchangedUnderConsent] for a statement that writes, locking as [lockedConsentInForce] does. */
internal fun exchangedUnderLockedConsent(supportRequestAlias: String, connection: String = REQUESTS_CONNECTION_SQL): String = """
    ($supportRequestAlias.sync_consent_id IS NOT NULL AND ${lockedConsentInForce("$supportRequestAlias.tenant_key", connection)})
"""

/**
 * SQL predicate: the request aliased [requestAlias] is exchanged with the connection of its delivery
 * [deliveryAlias] (ADR 0053 step 3). Epistola support's connection as before
 * ([exchangedUnderConsent]); another connection while the tenant shares support requests with it: the
 * request was handed to it then, with what it held.
 */
internal fun exchangedWith(requestAlias: String, deliveryAlias: String): String = """
    (($deliveryAlias.connection_id = $REQUESTS_CONNECTION_SQL AND ${exchangedUnderConsent(requestAlias)})
     OR ($deliveryAlias.connection_id <> $REQUESTS_CONNECTION_SQL AND ${consentInForce("$requestAlias.tenant_key", "$deliveryAlias.connection_id")}))
"""

/**
 * SQL predicate: the message or asset aliased [itemAlias] may go to the connection of the delivery
 * [deliveryAlias], its ledger row for that connection aliased [ledgerAlias] (a LEFT JOIN of
 * `support_request_delivered_items`). Epistola support's connection: written under consent, as before
 * ([createdUnderConsent]). Another connection: its ledger row exists, written while the tenant shared
 * with that connection ([SupportRequestLedger]).
 */
internal fun mayGoTo(itemAlias: String, ledgerAlias: String, deliveryAlias: String): String = """
    (($deliveryAlias.connection_id = $REQUESTS_CONNECTION_SQL AND ${createdUnderConsent(itemAlias)})
     OR ($deliveryAlias.connection_id <> $REQUESTS_CONNECTION_SQL AND $ledgerAlias.item_id IS NOT NULL))
"""

/**
 * SQL predicate, for a statement that writes: the request aliased [requestAlias] is exchanged with
 * connection [connectionId], reading the consent `FOR SHARE` ([exchangedWith] for one connection).
 */
internal fun exchangedWithLocked(requestAlias: String, connectionId: UUID): String = if (connectionId == REQUESTS_CONNECTION) {
    exchangedUnderLockedConsent(requestAlias)
} else {
    lockedConsentInForce("$requestAlias.tenant_key", connectionSql(connectionId))
}

/** [exchangedWith] for one connection, for a query. */
internal fun exchangedWithConnection(requestAlias: String, connectionId: UUID): String = if (connectionId == REQUESTS_CONNECTION) {
    exchangedUnderConsent(requestAlias)
} else {
    consentInForce("$requestAlias.tenant_key", connectionSql(connectionId))
}

/** [exchangedWith] for a statement that writes, reading the consent `FOR SHARE`. */
internal fun exchangedWithLockedRow(requestAlias: String, deliveryAlias: String): String = """
    (($deliveryAlias.connection_id = $REQUESTS_CONNECTION_SQL AND ${exchangedUnderLockedConsent(requestAlias)})
     OR ($deliveryAlias.connection_id <> $REQUESTS_CONNECTION_SQL AND ${lockedConsentInForce("$requestAlias.tenant_key", "$deliveryAlias.connection_id")}))
"""

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

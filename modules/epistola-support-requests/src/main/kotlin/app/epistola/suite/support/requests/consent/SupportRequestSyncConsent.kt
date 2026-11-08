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
 * The support connection feedback is sent over (ADR 0053). Feedback goes over one connection until
 * support requests have a delivery per connection (ADR 0053, rollout step 2); every consent and
 * default row it reads or writes is that connection's.
 */
internal val REQUESTS_CONNECTION: UUID = SupportConnections.DISCOVERED_ID

/** [REQUESTS_CONNECTION] as an SQL literal, for the predicates below, which are built into other statements. */
internal val REQUESTS_CONNECTION_SQL = "'$REQUESTS_CONNECTION'::uuid"

/** SQL that reads one tenant's [SupportRequestSyncConsent] row with the names of who changed it. */
internal val SELECT_SUPPORT_REQUEST_SYNC_CONSENT = """
    SELECT c.tenant_key, c.given_at AS consented_at, cu.display_name AS consented_by,
           c.withdrawn_at, wu.display_name AS withdrawn_by, c.follows_default
    FROM support_sharing_choice c
    LEFT JOIN users cu ON cu.id = c.given_by
    LEFT JOIN users wu ON wu.id = c.withdrawn_by
    WHERE c.connection_id = $REQUESTS_CONNECTION_SQL AND c.section = '$REQUESTS_SECTION' AND c.tenant_key = :tenantKey
"""

/**
 * SQL predicate, for a query with the tenant's key in [tenantKeyColumn], that the tenant's consent
 * is in force. The sync sweeps filter on it in SQL so they never load what they may not send.
 */
internal fun consentInForce(tenantKeyColumn: String): String = """
    EXISTS (SELECT 1 FROM support_sharing_choice fsc
            WHERE fsc.connection_id = $REQUESTS_CONNECTION_SQL AND fsc.section = '$REQUESTS_SECTION' AND fsc.tenant_key = $tenantKeyColumn AND fsc.withdrawn_at IS NULL)
"""

/**
 * SQL expression: the `grant_id` of [tenantKeyColumn]'s consent in force (one grant), or NULL.
 * What `support_requests.sync_consent_id` is set to when feedback is submitted.
 */
internal fun consentInForceId(tenantKeyColumn: String): String = """
    (SELECT fsc.grant_id FROM support_sharing_choice fsc
     WHERE fsc.connection_id = $REQUESTS_CONNECTION_SQL AND fsc.section = '$REQUESTS_SECTION' AND fsc.tenant_key = $tenantKeyColumn AND fsc.withdrawn_at IS NULL)
"""

/**
 * SQL predicate, for a query over `support_requests` aliased [feedbackAlias], that the feedback is exchanged
 * with Epistola support: the tenant's consent is in force, and the feedback was submitted under a
 * grant of consent (any grant: after a withdrawal and a new grant it carries on). Feedback submitted
 * without consent, or synced by the retired hub, is not: its comments, status changes and assets stay
 * in the installation. A comment or an asset also needs [createdUnderConsent].
 */
internal fun exchangedUnderConsent(supportRequestAlias: String): String = """
    ($supportRequestAlias.sync_consent_id IS NOT NULL
     AND EXISTS (SELECT 1 FROM support_sharing_choice fsc
                 WHERE fsc.connection_id = $REQUESTS_CONNECTION_SQL AND fsc.section = '$REQUESTS_SECTION' AND fsc.tenant_key = $supportRequestAlias.tenant_key AND fsc.withdrawn_at IS NULL))
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
internal fun lockedConsentInForce(tenantKeyColumn: String): String = """
    EXISTS (SELECT 1 FROM support_sharing_choice fsc
            WHERE fsc.connection_id = $REQUESTS_CONNECTION_SQL AND fsc.section = '$REQUESTS_SECTION' AND fsc.tenant_key = $tenantKeyColumn AND fsc.withdrawn_at IS NULL
            FOR SHARE)
"""

/** [consentInForceId] for a statement that writes, locking as [lockedConsentInForce] does. */
internal fun lockedConsentInForceId(tenantKeyColumn: String): String = """
    (SELECT fsc.grant_id FROM support_sharing_choice fsc
     WHERE fsc.connection_id = $REQUESTS_CONNECTION_SQL AND fsc.section = '$REQUESTS_SECTION' AND fsc.tenant_key = $tenantKeyColumn AND fsc.withdrawn_at IS NULL
     FOR SHARE)
"""

/** [exchangedUnderConsent] for a statement that writes, locking as [lockedConsentInForce] does. */
internal fun exchangedUnderLockedConsent(supportRequestAlias: String): String = """
    ($supportRequestAlias.sync_consent_id IS NOT NULL AND ${lockedConsentInForce("$supportRequestAlias.tenant_key")})
"""

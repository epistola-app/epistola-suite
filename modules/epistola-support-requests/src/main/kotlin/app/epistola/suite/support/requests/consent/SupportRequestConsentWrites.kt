// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.consent

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.support.requests.SyncStatus
import app.epistola.suite.support.sharing.SharingRestrictions
import org.jdbi.v3.core.Handle
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The writes behind giving and withdrawing consent, shared by a tenant's own choice
 * ([GrantSupportRequestSyncConsent], [WithdrawSupportRequestSyncConsent]) and the installation default
 * ([SetSupportRequestSharingDefault]), so both obey the same rules.
 */
internal object SupportRequestConsentWrites {
    /**
     * Gives [tenantKey]'s consent from [now], by [userId] (null when that user has no row), as its own
     * choice or ([followsDefault]) by the installation default. A new grant (a new `grant_id`) only
     * when it was not in force; either way the row records whose choice it is now.
     */
    fun grant(handle: Handle, tenantKey: TenantKey, userId: UUID?, now: OffsetDateTime, followsDefault: Boolean, connectionId: UUID = REQUESTS_CONNECTION) {
        SharingRestrictions.requireAllowed(handle, REQUESTS_SECTION, connectionId)
        handle.createUpdate(
            """
            INSERT INTO support_sharing_choice (connection_id, section, tenant_key, given_at, given_by, follows_default)
            VALUES (:connectionId, '$REQUESTS_SECTION', :tenantKey, :now, (SELECT id FROM users WHERE id = :userId), :followsDefault)
            ON CONFLICT (connection_id, section, tenant_key) DO UPDATE SET
                grant_id = CASE WHEN support_sharing_choice.withdrawn_at IS NULL THEN support_sharing_choice.grant_id ELSE gen_random_uuid() END,
                given_at = CASE WHEN support_sharing_choice.withdrawn_at IS NULL THEN support_sharing_choice.given_at ELSE EXCLUDED.given_at END,
                given_by = CASE WHEN support_sharing_choice.withdrawn_at IS NULL THEN support_sharing_choice.given_by ELSE EXCLUDED.given_by END,
                withdrawn_at = NULL,
                withdrawn_by = NULL,
                follows_default = EXCLUDED.follows_default
            """,
        )
            .bind("tenantKey", tenantKey)
            .bind("now", now)
            .bind("userId", userId)
            .bind("followsDefault", followsDefault)
            .bind("connectionId", connectionId)
            .execute()
    }

    /**
     * Withdraws [tenantKey]'s consent from [now] as its own choice, also when it never gave one (a row
     * that was never given records the choice), then stops what waits to be sent ([stopWaiting]).
     * Its first statement locks the consent row; see [WithdrawSupportRequestSyncConsent].
     *
     * @return the feedback items that were waiting and now stay here.
     */
    fun withdrawOwn(handle: Handle, tenantKey: TenantKey, userId: UUID?, now: OffsetDateTime, connectionId: UUID = REQUESTS_CONNECTION): Int {
        handle.createUpdate(
            """
            INSERT INTO support_sharing_choice (connection_id, section, tenant_key, given_at, withdrawn_at, withdrawn_by, follows_default)
            VALUES (:connectionId, '$REQUESTS_SECTION', :tenantKey, NULL, :now, (SELECT id FROM users WHERE id = :userId), FALSE)
            ON CONFLICT (connection_id, section, tenant_key) DO UPDATE SET
                withdrawn_at = COALESCE(support_sharing_choice.withdrawn_at, EXCLUDED.withdrawn_at),
                withdrawn_by = CASE WHEN support_sharing_choice.withdrawn_at IS NULL THEN EXCLUDED.withdrawn_by ELSE support_sharing_choice.withdrawn_by END,
                follows_default = FALSE
            """,
        )
            .bind("tenantKey", tenantKey)
            .bind("now", now)
            .bind("userId", userId)
            .bind("connectionId", connectionId)
            .execute()
        return stopWaiting(handle, listOf(tenantKey), connectionId)
    }

    /**
     * Everything of [tenantKeys] still waiting to be sent stays here: feedback still
     * [SyncStatus.PENDING] or [SyncStatus.FAILED] under a grant becomes [SyncStatus.NOT_CONFIGURED]
     * (feedback that read FAILED under no grant, as the retired hub left it, was never waiting and is
     * not counted), a status change not sent yet is no longer marked unsent, and comments and
     * screenshots lose the grant they were written under, so a later grant does not send them either.
     *
     * @return the feedback items that were waiting.
     */
    fun stopWaiting(handle: Handle, tenantKeys: List<TenantKey>, connectionId: UUID = REQUESTS_CONNECTION): Int {
        if (tenantKeys.isEmpty()) return 0
        val connection = connectionSql(connectionId)
        val keys = tenantKeys.map { it.value }
        // Waiting requests no longer go to the connection: their delivery goes, so they read NOT_CONFIGURED.
        val stopped = handle.createUpdate(
            """
            DELETE FROM support_request_deliveries d
            USING support_requests f
            WHERE f.tenant_key = d.tenant_key AND f.id = d.request_id
              AND d.connection_id = $connection AND d.tenant_key IN (<tenants>)
              AND d.state IN (:pending, :failed) AND f.sync_consent_id IS NOT NULL
            """,
        )
            .bindList("tenants", keys)
            .bind("pending", SyncStatus.PENDING.name)
            .bind("failed", SyncStatus.FAILED.name)
            .execute()
        // Never sent, so the connection does not handle them: the installation's inbox does again.
        handle.createUpdate(
            """
            UPDATE support_requests f SET handler_connection = NULL
            WHERE f.tenant_key IN (<tenants>) AND f.handler_connection = $connection
              AND NOT EXISTS (SELECT 1 FROM support_request_deliveries d
                              WHERE d.tenant_key = f.tenant_key AND d.request_id = f.id AND d.connection_id = $connection)
            """,
        )
            .bindList("tenants", keys)
            .execute()
        handle.createUpdate(
            "UPDATE support_request_deliveries SET status_unsent = FALSE, status_attempts = 0 " +
                "WHERE connection_id = $connection AND tenant_key IN (<tenants>) AND status_unsent",
        )
            .bindList("tenants", keys)
            .execute()
        // Another connection reads its ledger, not an item's grant: what was still to go there stays
        // here, and the grant Epistola support's connection reads is left as it is.
        if (connectionId != REQUESTS_CONNECTION) {
            SupportRequestLedger.forgetUnsent(handle, tenantKeys, connectionId)
            return stopped
        }
        handle.createUpdate(
            """
            UPDATE support_request_messages c SET sync_consent_id = NULL
            WHERE c.tenant_key IN (<tenants>) AND c.kind = 'REPLY' AND c.from_connection IS DISTINCT FROM $connection AND c.sync_consent_id IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM support_request_delivered_items dm
                              WHERE dm.tenant_key = c.tenant_key AND dm.request_id = c.request_id AND dm.item_id = c.id
                                AND dm.connection_id = $connection AND dm.remote_id IS NOT NULL)
            """,
        )
            .bindList("tenants", keys)
            .execute()
        handle.createUpdate(
            """
            UPDATE support_request_assets a SET sync_consent_id = NULL
            WHERE a.tenant_key IN (<tenants>) AND a.sync_consent_id IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM support_request_delivered_items da
                              WHERE da.tenant_key = a.tenant_key AND da.request_id = a.request_id AND da.item_id = a.id
                                AND da.connection_id = $connection AND da.sent_at IS NOT NULL)
            """,
        )
            .bindList("tenants", keys)
            .execute()
        return stopped
    }
}

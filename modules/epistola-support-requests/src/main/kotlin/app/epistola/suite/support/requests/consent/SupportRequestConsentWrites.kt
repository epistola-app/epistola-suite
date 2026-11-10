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
     * choice or ([followsDefault]) by the installation default. [now] and [userId] are recorded only
     * when it was not in force; either way the row records whose choice it is now.
     */
    fun grant(handle: Handle, tenantKey: TenantKey, userId: UUID?, now: OffsetDateTime, followsDefault: Boolean, connectionId: UUID = REQUESTS_CONNECTION) {
        SharingRestrictions.requireAllowed(handle, REQUESTS_SECTION, connectionId)
        handle.createUpdate(
            """
            INSERT INTO support_sharing_choice (connection_id, section, tenant_key, given_at, given_by, follows_default)
            VALUES (:connectionId, '$REQUESTS_SECTION', :tenantKey, :now, (SELECT id FROM users WHERE id = :userId), :followsDefault)
            ON CONFLICT (connection_id, section, tenant_key) DO UPDATE SET
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
     * Everything of [tenantKeys] still waiting to go to [connectionId] stays here: a request still
     * [SyncStatus.PENDING] or [SyncStatus.FAILED] there becomes [SyncStatus.NOT_CONFIGURED], a status
     * change not sent yet is no longer marked unsent, and messages and attachments still to go leave
     * its ledger, so sharing again does not send them either ([SupportRequestLedger.forgetUnsent]).
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
            DELETE FROM support_request_deliveries
            WHERE connection_id = $connection AND tenant_key IN (<tenants>) AND state IN (:pending, :failed)
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
        // What was still to go there stays here, also once the tenant shares again.
        SupportRequestLedger.forgetUnsent(handle, tenantKeys, connectionId)
        return stopped
    }
}

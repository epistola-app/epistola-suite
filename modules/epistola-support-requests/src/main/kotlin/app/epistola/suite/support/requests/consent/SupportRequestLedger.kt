// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.consent

import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import java.util.UUID

/**
 * What may go to a support connection, item by item (ADR 0053 §4), the same for every connection: a
 * row in `support_request_delivered_items` without `remote_id` or `sent_at` is an item still to go
 * there, one with them an item it has ([mayGoTo]).
 *
 * - When a request is handed to such a connection while the tenant shares support requests with it,
 *   everything it holds so far goes along ([recordHistory]), except internal notes and what came from
 *   that connection itself.
 * - Afterwards an item goes when it is written while the tenant still shares with that connection
 *   ([recordItem]): what is written while it does not never goes there, also once it shares again.
 * - A withdrawal drops what was still to go ([forgetUnsent]).
 *
 * Every write reads the consent `FOR SHARE`, as every write that may be sent does ([lockedConsentInForce]).
 */
internal object SupportRequestLedger {
    /** Everything [requestId] holds so far may go to [connectionId], if the tenant shares with it now. */
    fun recordHistory(handle: Handle, tenantKey: TenantKey, requestId: UUID, connectionId: UUID) {
        val connection = connectionSql(connectionId)
        handle.createUpdate(
            """
            INSERT INTO support_request_delivered_items (tenant_key, request_id, item_id, connection_id)
            SELECT m.tenant_key, m.request_id, m.id, $connection
            FROM support_request_messages m
            WHERE m.tenant_key = :tenantKey AND m.request_id = :requestId
              AND m.kind IN ('QUESTION', 'REPLY')
              AND m.from_connection IS DISTINCT FROM $connection
              AND ${lockedConsentInForce(":tenantKey", connection)}
            UNION ALL
            SELECT a.tenant_key, a.request_id, a.id, $connection
            FROM support_request_assets a
            WHERE a.tenant_key = :tenantKey AND a.request_id = :requestId
              AND ${lockedConsentInForce(":tenantKey", connection)}
            ON CONFLICT DO NOTHING
            """,
        )
            .bind("tenantKey", tenantKey)
            .bind("requestId", requestId)
            .execute()
    }

    /**
     * Item [itemId] of [requestId], just written, may go to every connection the request is delivered
     * to and that the tenant shares with now, except the connection it came from ([fromConnection]).
     */
    fun recordItem(handle: Handle, tenantKey: TenantKey, requestId: UUID, itemId: UUID, fromConnection: UUID? = null) {
        handle.createUpdate(
            """
            INSERT INTO support_request_delivered_items (tenant_key, request_id, item_id, connection_id)
            SELECT d.tenant_key, d.request_id, :itemId, d.connection_id
            FROM support_request_deliveries d
            WHERE d.tenant_key = :tenantKey AND d.request_id = :requestId
              AND d.connection_id IS DISTINCT FROM :fromConnection
              AND ${lockedConsentInForce(":tenantKey", "d.connection_id")}
            ON CONFLICT DO NOTHING
            """,
        )
            .bind("tenantKey", tenantKey)
            .bind("requestId", requestId)
            .bind("itemId", itemId)
            .bind("fromConnection", fromConnection)
            .execute()
    }

    /** What of [tenantKeys] was still to go to [connectionId] stays here. @return the items dropped. */
    fun forgetUnsent(handle: Handle, tenantKeys: List<TenantKey>, connectionId: UUID): Int {
        if (tenantKeys.isEmpty()) return 0
        return handle.createUpdate(
            """
            DELETE FROM support_request_delivered_items
            WHERE connection_id = :connectionId AND tenant_key IN (<tenants>) AND remote_id IS NULL AND sent_at IS NULL
            """,
        )
            .bind("connectionId", connectionId)
            .bindList("tenants", tenantKeys.map { it.value })
            .execute()
    }
}

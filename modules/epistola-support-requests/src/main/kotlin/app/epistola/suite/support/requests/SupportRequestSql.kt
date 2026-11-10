// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests

/**
 * SQL every read of a support request shares (ADR 0053 §3–4): the request (alias `f`), its question
 * (`q`, the message whose id is the request's) as the description, and its delivery (`d`) to
 * [connection] as the sync state: an SQL expression, by default the connection that handles the request
 * (Epistola support's while none does, as before). Without a delivery the request is not going there:
 * `NOT_CONFIGURED`. Add a `WHERE` on `f`.
 */
internal fun selectSupportRequest(connection: String = HANDLER_CONNECTION_SQL): String = """
    SELECT f.tenant_key, f.id, f.title, COALESCE(q.body, '') AS description, f.category, f.status, f.priority,
           f.requested_priority, f.source_url, f.console_logs, f.server_logs, f.metadata, f.created_by,
           f.created_at, f.updated_at, f.handler_connection, d.remote_ref AS external_ref, d.remote_url AS external_url,
           COALESCE(d.state, 'NOT_CONFIGURED') AS sync_status, COALESCE(d.attempts, 0) AS sync_attempts
    FROM support_requests f
    LEFT JOIN support_request_messages q ON q.tenant_key = f.tenant_key AND q.request_id = f.id AND q.id = f.id
    LEFT JOIN support_request_deliveries d ON d.tenant_key = f.tenant_key AND d.request_id = f.id
        AND d.connection_id = $connection
"""

/** The connection that handles a request (alias `f`); NULL while the installation's inbox does. */
internal const val HANDLER_CONNECTION_SQL = "f.handler_connection"

/** The connection that handles the request of the message aliased `c`; NULL while the inbox does. */
internal const val REPLY_HANDLER_CONNECTION_SQL = "(SELECT r.handler_connection FROM support_requests r WHERE r.tenant_key = c.tenant_key AND r.id = c.request_id)"

/** [selectSupportRequest] with the delivery to the connection that handles the request. */
internal val SELECT_SUPPORT_REQUEST = selectSupportRequest()

/**
 * SQL for replies as [SupportRequestComment] (alias `c` the message, `dm` its delivery to the
 * connection [connection], an SQL expression): written here is LOCAL, arriving over a connection
 * EXTERNAL; the external id is the connection's id for it either way. Add a `WHERE` on `c`.
 */
internal fun selectSupportRequestReply(connection: String = REPLY_HANDLER_CONNECTION_SQL): String = """
    SELECT c.tenant_key, c.request_id, c.id, c.body, c.author_name, c.author_email, c.author_id,
           CASE WHEN c.from_connection IS NULL THEN 'LOCAL' ELSE 'EXTERNAL' END AS source,
           COALESCE(c.remote_id, dm.remote_id) AS external_comment_id, c.created_at,
           COALESCE(dm.attempts, 0) AS sync_attempts
    FROM support_request_messages c
    LEFT JOIN support_request_delivered_items dm ON dm.tenant_key = c.tenant_key AND dm.request_id = c.request_id
        AND dm.item_id = c.id AND dm.connection_id = $connection
"""

/** [selectSupportRequestReply] for Epistola support's connection. */
internal val SELECT_SUPPORT_REQUEST_REPLY = selectSupportRequestReply()

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.commands

import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.support.requests.SupportRequestStatus
import app.epistola.suite.support.requests.SyncStatus
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION_SQL
import app.epistola.suite.support.requests.consent.connectionSql
import app.epistola.suite.support.requests.consent.exchangedUnderLockedConsent
import app.epistola.suite.support.requests.consent.exchangedWithLocked
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Records that the support service took the feedback, with the status it was sent with
 * ([sentStatus]): when the status was changed here meanwhile, the change is kept unsent
 * (`status_unsent`), so the next round sends it. [UpdateSupportRequestStatus] cannot mark it itself while
 * the feedback has no ref yet. Only while the feedback is exchanged under the consent in force: a
 * withdrawal that landed while it was on its way leaves the change here.
 */
data class UpdateSupportRequestSyncRef(
    val id: SupportRequestId,
    val externalRef: String,
    val externalUrl: String,
    val sentStatus: SupportRequestStatus,
    /** The connection it was sent to; Epistola support's by default. */
    val connectionId: UUID = REQUESTS_CONNECTION,
) : Command<Boolean>,
    SystemInternal

@Component
class UpdateSupportRequestSyncRefHandler(
    private val jdbi: Jdbi,
) : CommandHandler<UpdateSupportRequestSyncRef, Boolean> {
    override fun handle(command: UpdateSupportRequestSyncRef): Boolean = jdbi.withHandleUnchecked { handle ->
        val rows = handle.createUpdate(
            """
            INSERT INTO support_request_deliveries (tenant_key, request_id, connection_id, state, remote_ref, remote_url, status_unsent)
            SELECT f.tenant_key, f.id, ${connectionSql(command.connectionId)}, :syncStatus, :externalRef, :externalUrl,
                   (f.status <> :sentStatus AND ${exchangedWithLocked("f", command.connectionId)})
            FROM support_requests f
            WHERE f.tenant_key = :tenantKey AND f.id = :id
            ON CONFLICT (tenant_key, request_id, connection_id) DO UPDATE
                SET remote_ref = EXCLUDED.remote_ref, remote_url = EXCLUDED.remote_url, state = EXCLUDED.state,
                    status_unsent = EXCLUDED.status_unsent, status_attempts = 0, next_attempt_at = NULL
            """,
        )
            .bind("tenantKey", command.id.tenantKey)
            .bind("id", command.id.key.value)
            .bind("externalRef", command.externalRef)
            .bind("externalUrl", command.externalUrl)
            .bind("syncStatus", SyncStatus.SYNCED.name)
            .bind("sentStatus", command.sentStatus.name)
            .execute()
        rows > 0
    }
}

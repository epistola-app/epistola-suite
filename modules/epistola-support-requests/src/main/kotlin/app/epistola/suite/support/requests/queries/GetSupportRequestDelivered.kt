// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.queries

import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.support.requests.SupportRequest
import app.epistola.suite.support.requests.consent.connectionSql
import app.epistola.suite.support.requests.selectSupportRequest
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * A support request as delivered to [connectionId] (ADR 0053 step 3): its sync state and remote
 * reference are that connection's. For the sending round, which runs as the system. Null when there
 * is no such request.
 */
data class GetSupportRequestDelivered(
    val id: SupportRequestId,
    val connectionId: UUID,
) : Query<SupportRequest?>,
    // The sending round reads it for whoever wrote the request; nobody's page shows it.
    SystemInternal

@Component
class GetSupportRequestDeliveredHandler(private val jdbi: Jdbi) : QueryHandler<GetSupportRequestDelivered, SupportRequest?> {
    override fun handle(query: GetSupportRequestDelivered): SupportRequest? = jdbi.withHandleUnchecked { handle ->
        handle.createQuery("${selectSupportRequest(connectionSql(query.connectionId))} WHERE f.tenant_key = :tenantKey AND f.id = :id")
            .bind("tenantKey", query.id.tenantKey)
            .bind("id", query.id.key.value)
            .mapTo(SupportRequest::class.java)
            .findOne()
            .orElse(null)
    }
}

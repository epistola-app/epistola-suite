// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.queries

import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.common.ids.SupportRequestKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION_SQL
import app.epistola.suite.support.requests.consent.connectionSql
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component
import java.util.UUID

data class GetSupportRequestByExternalRef(
    override val tenantKey: TenantKey,
    val externalRef: String,
    /** The connection it came over; Epistola support's by default. */
    val connectionId: UUID = REQUESTS_CONNECTION,
) : Query<SupportRequestId?>,
    RequiresPermission {
    override val permission get() = Permission.SUPPORT_REQUEST_SUBMIT
}

@Component
class GetSupportRequestByExternalRefHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetSupportRequestByExternalRef, SupportRequestId?> {
    override fun handle(query: GetSupportRequestByExternalRef): SupportRequestId? = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            """
            SELECT request_id FROM support_request_deliveries
            WHERE tenant_key = :tenantKey AND connection_id = ${connectionSql(query.connectionId)} AND remote_ref = :externalRef
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .bind("externalRef", query.externalRef)
            .mapTo(java.util.UUID::class.java)
            .findOne()
            .orElse(null)
            ?.let { SupportRequestId(SupportRequestKey(it), TenantId(query.tenantKey)) }
    }
}

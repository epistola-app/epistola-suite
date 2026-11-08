// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.queries

import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.support.requests.SELECT_SUPPORT_REQUEST
import app.epistola.suite.support.requests.SupportRequest
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class GetSupportRequest(
    val id: SupportRequestId,
) : Query<SupportRequest?>,
    RequiresPermission {
    override val permission get() = Permission.SUPPORT_REQUEST_SUBMIT
    override val tenantKey get() = id.tenantKey
}

@Component
class GetSupportRequestHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetSupportRequest, SupportRequest?> {
    override fun handle(query: GetSupportRequest): SupportRequest? = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            "$SELECT_SUPPORT_REQUEST WHERE f.tenant_key = :tenantKey AND f.id = :id",
        )
            .bind("tenantKey", query.id.tenantKey)
            .bind("id", query.id.key.value)
            .mapTo(SupportRequest::class.java)
            .findOne()
            .orElse(null)
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.queries

import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.support.requests.SELECT_SUPPORT_REQUEST_REPLY
import app.epistola.suite.support.requests.SupportRequestComment
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class GetSupportRequestComments(
    val id: SupportRequestId,
) : Query<List<SupportRequestComment>>,
    RequiresPermission {
    override val permission get() = Permission.SUPPORT_REQUEST_SUBMIT
    override val tenantKey get() = id.tenantKey
}

@Component
class GetSupportRequestCommentsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetSupportRequestComments, List<SupportRequestComment>> {
    override fun handle(query: GetSupportRequestComments): List<SupportRequestComment> = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            "$SELECT_SUPPORT_REQUEST_REPLY WHERE c.tenant_key = :tenantKey AND c.request_id = :feedbackId AND c.kind = 'REPLY' ORDER BY c.seq",
        )
            .bind("tenantKey", query.id.tenantKey)
            .bind("feedbackId", query.id.key.value)
            .mapTo(SupportRequestComment::class.java)
            .list()
    }
}

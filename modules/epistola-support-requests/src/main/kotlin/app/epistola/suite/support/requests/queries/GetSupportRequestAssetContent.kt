// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.queries

import app.epistola.suite.common.ids.SupportRequestAssetId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.support.requests.SupportRequestAssetContent
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class GetSupportRequestAssetContent(
    val id: SupportRequestAssetId,
) : Query<SupportRequestAssetContent?>,
    RequiresPermission {
    override val permission get() = Permission.FEEDBACK_SUBMIT
    override val tenantKey get() = id.tenantKey
}

@Component
class GetSupportRequestAssetContentHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetSupportRequestAssetContent, SupportRequestAssetContent?> {
    override fun handle(query: GetSupportRequestAssetContent): SupportRequestAssetContent? = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            """
            SELECT content, content_type, filename
            FROM support_request_assets
            WHERE tenant_key = :tenantKey AND request_id = :feedbackId AND id = :id
            """,
        )
            .bind("tenantKey", query.id.tenantKey)
            .bind("feedbackId", query.id.supportRequestKey.value)
            .bind("id", query.id.key.value)
            .mapTo(SupportRequestAssetContent::class.java)
            .findOne()
            .orElse(null)
    }
}

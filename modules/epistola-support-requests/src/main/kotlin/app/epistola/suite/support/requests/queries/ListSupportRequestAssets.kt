// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.queries

import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.support.requests.SupportRequestAsset
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class ListSupportRequestAssets(
    val supportRequestId: SupportRequestId,
) : Query<List<SupportRequestAsset>>,
    RequiresPermission {
    override val permission get() = Permission.FEEDBACK_SUBMIT
    override val tenantKey get() = supportRequestId.tenantKey
}

@Component
class ListSupportRequestAssetsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListSupportRequestAssets, List<SupportRequestAsset>> {
    override fun handle(query: ListSupportRequestAssets): List<SupportRequestAsset> = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            """
            SELECT tenant_key, request_id, id, content_type, filename, created_at
            FROM support_request_assets
            WHERE tenant_key = :tenantKey AND request_id = :feedbackId
            ORDER BY created_at ASC
            """,
        )
            .bind("tenantKey", query.supportRequestId.tenantKey)
            .bind("feedbackId", query.supportRequestId.key.value)
            .mapTo(SupportRequestAsset::class.java)
            .list()
    }
}

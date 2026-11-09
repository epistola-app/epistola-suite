// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.apikeys.queries

import app.epistola.suite.apikeys.API_KEY_COLUMNS
import app.epistola.suite.apikeys.ApiKey
import app.epistola.suite.apikeys.toApiKey
import app.epistola.suite.common.ids.ApplicationKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

data class ListApiKeys(
    val tenantId: TenantKey,
    /** Only this application's keys; every key of the tenant when null. */
    val applicationId: ApplicationKey? = null,
) : Query<List<ApiKey>>,
    RequiresPermission {
    override val permission get() = Permission.CREDENTIALS_MANAGE
    override val tenantKey get() = tenantId
}

@Component
class ListApiKeysHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListApiKeys, List<ApiKey>> {

    override fun handle(query: ListApiKeys): List<ApiKey> = jdbi.withHandle<List<ApiKey>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT $API_KEY_COLUMNS,
                   COALESCE(u.display_name, u.email) AS created_by_display_name
            FROM api_keys k
            LEFT JOIN applications a ON a.id = k.application_id
            LEFT JOIN users u ON u.id = k.created_by
            WHERE k.tenant_key = :tenantId
              ${if (query.applicationId != null) "AND k.application_id = :applicationId" else ""}
            ORDER BY k.created_at DESC
            """,
        )
            .bind("tenantId", query.tenantId)
            .also { q -> query.applicationId?.let { q.bind("applicationId", it) } }
            .map { rs, _ -> rs.toApiKey(withDisplayName = true) }
            .list()
    }
}

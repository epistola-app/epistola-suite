// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.apikeys.queries

import app.epistola.suite.apikeys.ApiKey
import app.epistola.suite.apikeys.toApiKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

data class ListApiKeys(
    val tenantId: TenantKey,
) : Query<List<ApiKey>>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_USERS
    override val tenantKey get() = tenantId
}

@Component
class ListApiKeysHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListApiKeys, List<ApiKey>> {

    override fun handle(query: ListApiKeys): List<ApiKey> = jdbi.withHandle<List<ApiKey>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT k.id, k.tenant_key, k.name, k.key_prefix, k.enabled, k.created_at,
                   k.last_used_at, k.expires_at, k.created_by, k.revoked_at, k.revoked_by, k.roles,
                   COALESCE(u.display_name, u.email) AS created_by_display_name
            FROM api_keys k
            LEFT JOIN users u ON u.id = k.created_by
            WHERE k.tenant_key = :tenantId
            ORDER BY k.created_at DESC
            """,
        )
            .bind("tenantId", query.tenantId)
            .map { rs, _ -> rs.toApiKey(withDisplayName = true) }
            .list()
    }
}

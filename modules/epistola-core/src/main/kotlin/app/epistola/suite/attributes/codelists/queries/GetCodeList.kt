// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.queries

import app.epistola.suite.attributes.codelists.model.CodeList
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetCodeList(
    val id: CodeListId,
) : Query<CodeList?>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = id.tenantKey
}

@Component
class GetCodeListHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetCodeList, CodeList?> {
    override fun handle(query: GetCodeList): CodeList? = jdbi.withHandle<CodeList?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT cl.slug, cl.tenant_key, cl.catalog_key, cl.display_name, cl.description,
                   cl.source_type, cl.source_url, cl.auth_type, cl.credential,
                   cl.last_refreshed_at, cl.last_refresh_error,
                   cl.created_at, cl.updated_at,
                   c.type AS catalog_type
            FROM code_lists cl
            JOIN catalogs c ON c.tenant_key = cl.tenant_key AND c.id = cl.catalog_key
            WHERE cl.tenant_key = :tenantKey AND cl.catalog_key = :catalogKey AND cl.slug = :slug
            """,
        )
            .bind("tenantKey", query.id.tenantKey)
            .bind("catalogKey", query.id.catalogKey)
            .bind("slug", query.id.key)
            .mapTo<CodeList>()
            .findOne()
            .orElse(null)
    }
}

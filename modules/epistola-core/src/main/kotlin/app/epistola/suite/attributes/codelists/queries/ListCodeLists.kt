package app.epistola.suite.attributes.codelists.queries

import app.epistola.suite.attributes.codelists.model.CodeList
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Lists code lists owned by a tenant, optionally filtered to a single catalog.
 *
 * Each row carries the owning catalog's `type` so the UI can gate edit
 * affordances (AUTHORED is editable, SUBSCRIBED is read-only). Entries are
 * not fetched here; use `ListCodeListEntries` for that.
 */
data class ListCodeLists(
    val tenantId: TenantId,
    val catalogKey: CatalogKey? = null,
) : Query<List<CodeList>>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey get() = tenantId.key
}

@Component
class ListCodeListsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListCodeLists, List<CodeList>> {
    override fun handle(query: ListCodeLists): List<CodeList> = jdbi.withHandle<List<CodeList>, Exception> { handle ->
        val sql = buildString {
            append(
                """
                SELECT cl.slug, cl.tenant_key, cl.catalog_key, cl.display_name, cl.description,
                       cl.source_type, cl.source_url, cl.auth_type, cl.credential,
                       cl.last_refreshed_at, cl.last_refresh_error,
                       cl.created_at, cl.updated_at,
                       c.type AS catalog_type
                FROM code_lists cl
                JOIN catalogs c ON c.tenant_key = cl.tenant_key AND c.id = cl.catalog_key
                WHERE cl.tenant_key = :tenantId
                """,
            )
            if (query.catalogKey != null) {
                append(" AND cl.catalog_key = :catalogKey")
            }
            append(" ORDER BY cl.display_name ASC")
        }

        val q = handle.createQuery(sql).bind("tenantId", query.tenantId.key)
        if (query.catalogKey != null) {
            q.bind("catalogKey", query.catalogKey)
        }
        q.mapTo<CodeList>().list()
    }
}

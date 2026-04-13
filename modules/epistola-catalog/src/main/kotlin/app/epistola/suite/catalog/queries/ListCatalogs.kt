package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListCatalogs(
    override val tenantKey: TenantKey,
) : Query<List<Catalog>>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS
}

@Component
class ListCatalogsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListCatalogs, List<Catalog>> {

    override fun handle(query: ListCatalogs): List<Catalog> = jdbi.withHandle<List<Catalog>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT id, tenant_key, name, description, type, mutability, source_url, source_auth_type, source_auth_credential, installed_release_version, installed_at, created_at, last_modified
            FROM catalogs
            WHERE tenant_key = :tenantKey
            ORDER BY name
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .mapTo<Catalog>()
            .list()
    }
}

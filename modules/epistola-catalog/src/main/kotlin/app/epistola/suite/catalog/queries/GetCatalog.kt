package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetCatalog(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<Catalog?>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS
}

@Component
class GetCatalogHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetCatalog, Catalog?> {

    override fun handle(query: GetCatalog): Catalog? = jdbi.withHandle<Catalog?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT id, tenant_key, name, description, type, mutability, source_url, source_auth_type, source_auth_credential, installed_release_version, installed_at, created_at, last_modified
            FROM catalogs
            WHERE tenant_key = :tenantKey AND id = :catalogKey
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .bind("catalogKey", query.catalogKey)
            .mapTo<Catalog>()
            .findOne()
            .orElse(null)
    }
}

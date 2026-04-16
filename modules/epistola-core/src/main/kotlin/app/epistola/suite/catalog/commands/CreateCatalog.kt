package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.executeOrThrowDuplicate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class CreateCatalog(
    override val tenantKey: TenantKey,
    val id: CatalogKey,
    val name: String,
    val description: String? = null,
) : Command<Catalog>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS
}

@Component
class CreateCatalogHandler(
    private val jdbi: Jdbi,
) : CommandHandler<CreateCatalog, Catalog> {

    override fun handle(command: CreateCatalog): Catalog = executeOrThrowDuplicate("catalog", command.id.value) {
        jdbi.withHandle<Catalog, Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO catalogs (id, tenant_key, name, description, type, created_at, last_modified)
                VALUES (:id, :tenantKey, :name, :description, 'AUTHORED', NOW(), NOW())
                """,
            )
                .bind("id", command.id)
                .bind("tenantKey", command.tenantKey)
                .bind("name", command.name)
                .bind("description", command.description)
                .execute()

            handle.createQuery(
                """
                SELECT id, tenant_key, name, description, type, source_url, source_auth_type, source_auth_credential, installed_release_version, installed_at, created_at, last_modified
                FROM catalogs
                WHERE tenant_key = :tenantKey AND id = :id
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("id", command.id)
                .mapTo<Catalog>()
                .one()
        }
    }
}

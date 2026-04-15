package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogInUseException
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.queries.FindCatalogCrossReferences
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

data class UnregisterCatalog(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val force: Boolean = false,
) : Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS
}

@Component
class UnregisterCatalogHandler(
    private val jdbi: Jdbi,
) : CommandHandler<UnregisterCatalog, Boolean> {

    override fun handle(command: UnregisterCatalog): Boolean {
        require(command.catalogKey != CatalogKey.DEFAULT) {
            "The default catalog cannot be deleted"
        }

        if (!command.force) {
            val references = FindCatalogCrossReferences(command.tenantKey, command.catalogKey).query()
            if (references.isNotEmpty()) {
                throw CatalogInUseException(command.catalogKey, references)
            }
        }

        return jdbi.withHandle<Boolean, Exception> { handle ->
            val deleted = handle.createUpdate(
                """
                DELETE FROM catalogs
                WHERE tenant_key = :tenantKey AND id = :id
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("id", command.catalogKey)
                .execute()

            deleted > 0
        }
    }
}

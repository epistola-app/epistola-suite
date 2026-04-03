package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

data class UnregisterCatalog(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS
}

@Component
class UnregisterCatalogHandler(
    private val jdbi: Jdbi,
) : CommandHandler<UnregisterCatalog, Boolean> {

    override fun handle(command: UnregisterCatalog): Boolean {
        val deleted = jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(
                """
                DELETE FROM catalogs
                WHERE tenant_key = :tenantKey AND id = :id
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("id", command.catalogKey)
                .execute()
        }
        return deleted > 0
    }
}

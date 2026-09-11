// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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
    override val permission get() = Permission.CATALOG_MANAGE
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
            // Relocation leaves an alias at every address a resource moved out of. Those pointing
            // at resources elsewhere have no foreign key on this catalog, so they would outlive it
            // and keep the addresses reserved for a catalog registered later under the same key --
            // with no page left to release them from. The catalog's history goes with it.
            handle.createUpdate(
                """
                DELETE FROM catalog_resource_aliases
                WHERE tenant_key = :tenantKey AND catalog_key = :id
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("id", command.catalogKey)
                .execute()

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

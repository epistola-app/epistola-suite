// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogInUseException
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.queries.FindCatalogCrossReferences
import app.epistola.suite.catalog.revisions.ResourceRevisionStore
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
    private val revisions: ResourceRevisionStore,
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

            // The catalog's releases cascade away with it, and their entries with them -- which
            // leaves the revisions those entries named reachable from nothing, and permanent:
            // nothing else deletes a revision, and `revision_binaries` would hold the bytes they
            // named against the content sweep for good. Collecting here is what makes deleting a
            // catalog actually free its content.
            if (deleted > 0) revisions.collectUnreferenced(handle, command.tenantKey)

            deleted > 0
        }
    }
}

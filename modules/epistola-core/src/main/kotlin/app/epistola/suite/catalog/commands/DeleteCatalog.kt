// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogInUseException
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.queries.FindCatalogCrossReferences
import app.epistola.suite.catalog.revisions.ReleaseInUse
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.environments.DraftDeploymentStore
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

data class DeleteCatalog(
    val catalog: CatalogId,
    val force: Boolean = false,
) : Command<Boolean>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.CATALOG_MANAGE
}

@Component
class DeleteCatalogHandler(
    private val jdbi: Jdbi,
) : CommandHandler<DeleteCatalog, Boolean> {

    override fun handle(command: DeleteCatalog): Boolean {
        // The suite installs it in every tenant on every start, so a delete would not stick; what uses it is
        // refused below anyway.
        require(command.catalogKey != SYSTEM_CATALOG_KEY) {
            "The system catalog cannot be deleted"
        }

        if (!command.force) {
            val references = FindCatalogCrossReferences(command.catalog).query()
            if (references.isNotEmpty()) {
                throw CatalogInUseException(command.catalogKey, references)
            }
        }

        return jdbi.withHandle<Boolean, Exception> { handle ->
            // Its releases go with it, so none of them may still be served or depended on. Not
            // bypassed by force: force overrides references from authoring content, not rendering.
            ReleaseInUse.requireCatalogUnused(handle, command.catalog)
            // A draft an environment serves goes with the catalog, its deployment ended and logged.
            DraftDeploymentStore.endForCatalog(handle, command.catalog)
            // Declarations in other catalogs' drafts that build on one of its drafts go with those
            // drafts: such a draft names the catalog's content, so forcing past it drops them.
            handle.createUpdate(
                """
                DELETE FROM catalog_version_dependencies
                WHERE tenant_key = :tenantKey AND release_version IS NULL
                  AND target_version_id IN (SELECT version_id FROM catalog_versions WHERE tenant_key = :tenantKey AND catalog_id = ${catalogAt("tenantKey", "id")})
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bindCatalog("id", command.catalog)
                .execute()
            val deleted = handle.createUpdate(
                """
                DELETE FROM catalogs
                WHERE tenant_key = :tenantKey AND catalog_id = epistola_catalog_id(:tenantKey, :id_ns, :id)
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bindCatalog("id", command.catalog)
                .execute()

            // The catalog's releases and draft cascade away with it, and their rows with them. The
            // contents those rows named are the collector's now (ResourceContentCollector, ADR 0033):
            // past the grace period, it takes them with their binaries and references.

            deleted > 0
        }
    }
}

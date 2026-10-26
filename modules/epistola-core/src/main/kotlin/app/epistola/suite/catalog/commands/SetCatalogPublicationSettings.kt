// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.CatalogPublicationPolicy
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.findCatalog
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

data class SetCatalogPublicationSettings(
    val catalog: CatalogId,
    val policy: CatalogPublicationPolicy,
) : Command<Catalog>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission = Permission.CATALOG_MANAGE
}

@Component
class SetCatalogPublicationSettingsHandler(
    private val jdbi: Jdbi,
) : CommandHandler<SetCatalogPublicationSettings, Catalog> {
    override fun handle(command: SetCatalogPublicationSettings): Catalog {
        requireCatalogEditable(command.catalog)
        return jdbi.withHandle<Catalog, Exception> { handle ->
            val updated = handle.createUpdate(
                """
                UPDATE catalogs
                SET exchange_publication_policy = :policy
                WHERE tenant_key = :tenantKey AND catalog_id = epistola_catalog_id(:tenantKey, :catalogKey_ns, :catalogKey)
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bindCatalog("catalogKey", command.catalog)
                .bind("policy", command.policy)
                .execute()
            // Read back with its current release, a query over its released rows (ADR 0036).
            handle.findCatalog(command.catalog)?.takeIf { updated > 0 } ?: throw CatalogNotFoundException(command.catalogKey)
        }
    }
}
